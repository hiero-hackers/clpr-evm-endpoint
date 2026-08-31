// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.sei;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiCommit;
import com.hedera.hapi.node.state.clpr.SeiCommitSig;
import com.hedera.hapi.node.state.clpr.SeiHeader;
import com.hedera.hapi.node.state.clpr.SeiSignedHeader;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.hiero.clpr.relay.evm.JsonRpcException;

/**
 * HTTP client for the CometBFT RPC API exposed by Sei nodes.
 *
 * <p>Provides three operations needed by {@link SeiBundleConstructor}:
 * <ul>
 *   <li>{@link #getSignedHeader} — fetches the signed block header used to anchor state proofs.</li>
 *   <li>{@link #getValidatorSet} — fetches the validator set for trust-anchor rotation.</li>
 *   <li>{@link #abciQuery} — queries a single EVM storage slot with ICS-23 proof ops.</li>
 * </ul>
 *
 * <p>All requests are POST JSON-RPC calls against the CometBFT RPC endpoint (default port 26657).
 * Sei's GET-URI endpoints return the payload <em>without</em> the JSON-RPC {@code result} envelope,
 * so POST is used to obtain the canonical {@code {jsonrpc, id, result}} (or {@code error}) response.
 * Transient errors are retried with exponential backoff, mirroring the behaviour of
 * {@code EvmJsonRpcClient}.
 */
public class CometBftRpcClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long INITIAL_BACKOFF_MS = 200L;

    // CometBFT block_id_flag values
    private static final int BLOCK_ID_FLAG_COMMIT = 2;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final int maxRetries;
    private final Duration requestTimeout;

    /**
     * Result of an ABCI query with ICS-23 proof ops.
     *
     * @param value          the proven 32-byte slot value
     * @param iavlProof      protobuf-serialized {@code ics23.CommitmentProof} (existence) proving
     *                       the key/value up to the {@code evm} store root
     * @param multistoreProof protobuf-serialized {@code ics23.CommitmentProof} (existence) proving
     *                       the {@code evm} store root up to the block's {@code app_hash}
     */
    public record SeiAbciProofResult(Bytes value, Bytes iavlProof, Bytes multistoreProof) {}

    /**
     * Create a client targeting the given CometBFT RPC URL.
     *
     * @param cometBftRpcUrl base URL of the CometBFT RPC endpoint (e.g. {@code http://localhost:26657})
     * @param maxRetries       maximum number of retry attempts on transient errors
     * @param requestTimeout   per-request response timeout
     */
    public CometBftRpcClient(final String cometBftRpcUrl, final int maxRetries, final Duration requestTimeout) {
        // Normalize: strip any trailing slash so path concatenation is consistent.
        this.baseUrl = cometBftRpcUrl.endsWith("/")
                ? cometBftRpcUrl.substring(0, cometBftRpcUrl.length() - 1)
                : cometBftRpcUrl;
        this.maxRetries = maxRetries;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch the signed header (block header + commit) at the given CometBFT height.
     *
     * <p>When proving contract state at height H, callers should request height H+1: the
     * CometBFT {@code app_hash} in header H+1 is the Merkle root of the application state after
     * executing block H, so ICS-23 proofs queried at height H must be anchored to header H+1.
     *
     * @param height the CometBFT block height to fetch
     * @return the signed header at {@code height}
     */
    public SeiSignedHeader getSignedHeader(final long height) {
        final var params = MAPPER.createObjectNode().put("height", Long.toString(height));
        final var result = rpc("commit", params);
        return parseSignedHeader(result.get("signed_header"));
    }

    /**
     * Fetch the validator set active at the given CometBFT height.
     *
     * <p>Fetches a single page of up to 100 validators. A validator set larger than one page is
     * rejected with a {@link JsonRpcException} rather than silently truncated: a partial set hashes
     * to a different {@code validators_hash} than the header advertises, which would make the
     * resulting bundle/config payload fail verification on the peer with no diagnostic here.
     *
     * @param height the CometBFT block height
     * @return the validator set at {@code height}
     */
    public SeiValidatorSet getValidatorSet(final long height) {
        final var params = MAPPER.createObjectNode()
                .put("height", Long.toString(height))
                .put("per_page", "100")
                .put("page", "1");
        final var result = rpc("validators", params);
        final var validators = result.path("validators");
        // CometBFT clamps per_page to 100. Fail fast if the set spans more than one page rather than
        // returning a truncated set that hashes to the wrong validators_hash downstream.
        final int total = result.path("total").asInt(validators.size());
        if (validators.size() < total) {
            throw new JsonRpcException(
                    -32000,
                    "validator set at height " + height + " has " + total
                            + " validators, exceeding the single-page limit of 100; pagination is not supported");
        }
        return parseValidatorSet(validators);
    }

    /**
     * Perform an ABCI query for a single storage key with ICS-23 proof ops.
     *
     * <p>Queries the {@code /store/evm/key} path. The CometBFT node returns two proof ops:
     * <ol>
     *   <li>{@code ics23:iavl} — proves key/value up to the {@code evm} module store root.</li>
     *   <li>{@code ics23:simple} — proves the {@code evm} store root up to {@code app_hash}.</li>
     * </ol>
     *
     * <p>For EVM contract storage the key must be
     * {@code 0x03 || 20-byte contract address || 32-byte storage slot}.
     *
     * @param key    the full 53-byte store key
     * @param height the CometBFT block height to prove against
     * @return the proven value and both proof op bytes
     */
    public SeiAbciProofResult abciQuery(final byte[] key, final long height) {
        final var hexKey = toHex(key);
        // CometBFT's JSON-RPC decodes the HexBytes `data` param with hex.DecodeString, which
        // rejects a "0x" prefix — so send bare hex here (the EVM JSON-RPC, by contrast, wants "0x").
        final var params = MAPPER.createObjectNode()
                .put("path", "/store/evm/key")
                .put("data", hexKey.substring(2))
                .put("height", Long.toString(height))
                .put("prove", true);
        final var result = rpc("abci_query", params);
        final var response = result.path("response");

        final int code = response.path("code").asInt(0);
        if (code != 0) {
            throw new JsonRpcException(
                    -32000,
                    "ABCI query failed with code " + code + ": "
                            + response.path("log").asText(""));
        }

        final var valueB64 = response.path("value").asText("");
        final var value = valueB64.isEmpty()
                ? Bytes.EMPTY
                : Bytes.wrap(Base64.getDecoder().decode(valueB64));

        // Sei returns the proof ops under "proofOps" (camelCase), not "proof_ops".
        final var opsNode = response.path("proofOps").path("ops");
        if (opsNode.isMissingNode() || opsNode.size() < 2) {
            throw new JsonRpcException(-32000, "Expected 2 proof ops but got " + opsNode.size() + " for key " + hexKey);
        }
        final var iavlProof = decodeBase64Field(opsNode.get(0), "data");
        final var multistoreProof = decodeBase64Field(opsNode.get(1), "data");

        return new SeiAbciProofResult(value, iavlProof, multistoreProof);
    }

    // -------------------------------------------------------------------------
    // Parsing — signed header
    // -------------------------------------------------------------------------

    private static SeiSignedHeader parseSignedHeader(final JsonNode json) {
        return SeiSignedHeader.newBuilder()
                .header(parseHeader(json.get("header")))
                .commit(parseCommit(json.get("commit")))
                .build();
    }

    private static SeiHeader parseHeader(final JsonNode h) {
        return SeiHeader.newBuilder()
                .versionBlock(h.path("version").path("block").asLong(11))
                .versionApp(h.path("version").path("app").asLong(0))
                .chainId(h.get("chain_id").asText())
                .height(Long.parseLong(h.get("height").asText()))
                .time(parseTimestamp(h.path("time").asText("")))
                .lastBlockId(parseBlockRef(h.path("last_block_id")))
                .lastCommitHash(decodeHexField(h, "last_commit_hash"))
                .dataHash(decodeHexField(h, "data_hash"))
                .validatorsHash(decodeHexField(h, "validators_hash"))
                .nextValidatorsHash(decodeHexField(h, "next_validators_hash"))
                .consensusHash(decodeHexField(h, "consensus_hash"))
                .appHash(decodeHexField(h, "app_hash"))
                .lastResultsHash(decodeHexField(h, "last_results_hash"))
                .evidenceHash(decodeHexField(h, "evidence_hash"))
                .proposerAddress(decodeHexField(h, "proposer_address"))
                .build();
    }

    private static SeiBlockRef parseBlockRef(final JsonNode ref) {
        if (ref == null || ref.isMissingNode() || ref.isNull()) return SeiBlockRef.DEFAULT;
        return SeiBlockRef.newBuilder()
                .hash(decodeHexField(ref, "hash"))
                .partSetTotal(ref.path("parts").path("total").asInt(0))
                .partSetHash(decodeHexField(ref.path("parts"), "hash"))
                .build();
    }

    private static SeiCommit parseCommit(final JsonNode c) {
        final var blockIdNode = c.path("block_id");
        return SeiCommit.newBuilder()
                .round(c.path("round").asInt(0))
                .partSetTotal(blockIdNode.path("parts").path("total").asInt(0))
                .partSetHash(decodeHexField(blockIdNode.path("parts"), "hash"))
                .signersBits(buildSignerBits(c.path("signatures")))
                .signatures(buildCommitSigs(c.path("signatures")))
                .build();
    }

    /**
     * Builds a compact bitset where bit {@code i} (MSB-first within each byte) is set when
     * validator {@code i} contributed a COMMIT vote.
     */
    private static Bytes buildSignerBits(final JsonNode sigs) {
        final int count = sigs.size();
        final var bits = new byte[(count + 7) / 8];
        for (int i = 0; i < count; i++) {
            if (blockIdFlag(sigs.get(i)) == BLOCK_ID_FLAG_COMMIT) {
                bits[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
        }
        return Bytes.wrap(bits);
    }

    private static List<SeiCommitSig> buildCommitSigs(final JsonNode sigs) {
        final var result = new ArrayList<SeiCommitSig>();
        for (final var sig : sigs) {
            if (blockIdFlag(sig) == BLOCK_ID_FLAG_COMMIT) {
                result.add(SeiCommitSig.newBuilder()
                        .timestamp(parseTimestamp(sig.path("timestamp").asText("")))
                        .signature(decodeBase64Field(sig, "signature"))
                        .build());
            }
        }
        return result;
    }

    private static int blockIdFlag(final JsonNode sig) {
        final var node = sig.get("block_id_flag");
        if (node == null) return 1;
        if (node.isNumber()) return node.asInt(1);
        return switch (node.asText("")) {
            case "BLOCK_ID_FLAG_COMMIT" -> 2;
            case "BLOCK_ID_FLAG_NIL" -> 3;
            default -> 1; // BLOCK_ID_FLAG_ABSENT or unknown
        };
    }

    // -------------------------------------------------------------------------
    // Parsing — validator set
    // -------------------------------------------------------------------------

    private static SeiValidatorSet parseValidatorSet(final JsonNode validators) {
        final var entries = new ArrayList<SeiValidatorEntry>();
        int index = 0;
        for (final var v : validators) {
            final var pubKeyType = v.path("pub_key").path("type").asText("");
            if (!pubKeyType.contains("Ed25519")) {
                // Don't silently drop: omitting a validator changes the set's Merkle hash (and the
                // positional index used by the commit signers bitset), so a partial set fails
                // verification on the peer. Fail fast instead, naming the offending validator.
                throw new JsonRpcException(
                        -32000,
                        "validator " + index + " has unsupported public key type '" + pubKeyType
                                + "'; the Sei verifier only supports Ed25519");
            }
            final var pubKeyBytes = Bytes.wrap(
                    Base64.getDecoder().decode(v.path("pub_key").path("value").asText()));
            final var votingPower = Long.parseLong(v.path("voting_power").asText("0"));
            entries.add(SeiValidatorEntry.newBuilder()
                    .ed25519PubKey(pubKeyBytes)
                    .votingPower(votingPower)
                    .build());
            index++;
        }
        return SeiValidatorSet.newBuilder().validators(entries).build();
    }

    // -------------------------------------------------------------------------
    // Parsing — shared helpers
    // -------------------------------------------------------------------------

    private static Timestamp parseTimestamp(final String rfc3339) {
        if (rfc3339.isEmpty()) return Timestamp.DEFAULT;
        final var instant = Instant.parse(rfc3339);
        return Timestamp.newBuilder()
                .seconds(instant.getEpochSecond())
                .nanos(instant.getNano())
                .build();
    }

    private static Bytes decodeBase64Field(final JsonNode node, final String field) {
        if (node == null || node.isMissingNode()) return Bytes.EMPTY;
        final var child = node.get(field);
        if (child == null || child.isNull()) return Bytes.EMPTY;
        final var text = child.asText("");
        if (text.isEmpty()) return Bytes.EMPTY;
        return Bytes.wrap(Base64.getDecoder().decode(text));
    }

    /**
     * Decode a hex-encoded JSON string field. CometBFT renders block hashes and validator addresses
     * (header hashes, block-id hashes, proposer_address) as upper-case hex — unlike public keys,
     * commit signatures and ABCI proof data, which are base64. Tolerant of empty/absent fields.
     */
    private static Bytes decodeHexField(final JsonNode node, final String field) {
        if (node == null || node.isMissingNode()) return Bytes.EMPTY;
        final var child = node.get(field);
        if (child == null || child.isNull()) return Bytes.EMPTY;
        final var text = child.asText("");
        if (text.isEmpty()) return Bytes.EMPTY;
        final var hex = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text;
        try {
            return Bytes.wrap(HexFormat.of().parseHex(hex));
        } catch (final IllegalArgumentException e) {
            throw new JsonRpcException(-32700, "Field '" + field + "' is not valid hex: " + e.getMessage());
        }
    }

    private static String toHex(final byte[] bytes) {
        return "0x" + HexFormat.of().formatHex(bytes);
    }

    // -------------------------------------------------------------------------
    // HTTP transport
    // -------------------------------------------------------------------------

    private JsonNode rpc(final String method, final ObjectNode params) {
        final var requestBody = MAPPER.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", 1);
        requestBody.put("method", method);
        requestBody.set("params", params);
        final String bodyStr;
        try {
            bodyStr = MAPPER.writeValueAsString(requestBody);
        } catch (final JsonProcessingException e) {
            throw new JsonRpcException(-32700, "Failed to encode CometBFT RPC request: " + e.getMessage());
        }

        IOException lastIoException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleepBackoff(attempt);
            }
            try {
                final var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                        .timeout(requestTimeout)
                        .build();

                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                final int status = response.statusCode();
                if (status == 429 || status == 503) {
                    if (attempt < maxRetries) continue;
                    throw new JsonRpcException(-32000, "HTTP " + status + " after " + maxRetries + " retries");
                }
                if (status < 200 || status >= 300) {
                    throw new JsonRpcException(-32000, "HTTP error " + status);
                }

                final JsonNode root;
                try {
                    root = MAPPER.readTree(response.body());
                } catch (final JsonProcessingException e) {
                    throw new JsonRpcException(-32700, "Invalid JSON in CometBFT response: " + e.getMessage());
                }

                final var errorNode = root.get("error");
                if (errorNode != null && !errorNode.isNull()) {
                    throw new JsonRpcException(
                            errorNode.path("code").asInt(-32000),
                            errorNode.path("message").asText("unknown CometBFT RPC error"));
                }

                // Unwrap and validate the JSON-RPC result envelope here so callers can dereference
                // result fields without risking a NullPointerException on a malformed response.
                final var resultNode = root.get("result");
                if (resultNode == null || resultNode.isNull()) {
                    throw new JsonRpcException(-32000, "CometBFT RPC '" + method + "' returned no 'result' field");
                }
                return resultNode;

            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JsonRpcException(-32000, "Request interrupted");
            } catch (final IOException e) {
                lastIoException = e;
            }
        }
        throw new JsonRpcException(
                -32000,
                "CometBFT RPC failed after " + maxRetries + " retries: "
                        + (lastIoException != null ? lastIoException.getMessage() : "unknown"));
    }

    private static void sleepBackoff(final int attempt) {
        final long delayMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
        try {
            Thread.sleep(delayMs);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
