// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal generic JSON-RPC caller for the handful of methods the relay's own
 * {@code EvmJsonRpcClient} does not expose.
 *
 * <p>The relay client is deliberately narrow — it only implements what the relay needs — and its
 * request plumbing is private, so there is no way to reach {@code eth_getCode} or a full block body
 * through it. Rather than widening production code for test convenience, the E2E framework carries
 * this small escape hatch. Everything the relay client <em>does</em> cover still goes through it, so
 * the tests exercise the same decoding paths as production.
 *
 * <p>Pinned to HTTP/1.1: Ethereum nodes do not honour the HTTP/2 upgrade and Java's client
 * otherwise wastes a round trip per request discovering that.
 */
public final class RawJsonRpc implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final AtomicLong ids = new AtomicLong(1);

    /**
     * @param jsonRpcUrl the node's HTTP JSON-RPC URL
     */
    public RawJsonRpc(final String jsonRpcUrl) {
        this.endpoint = URI.create(jsonRpcUrl);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Invoke a JSON-RPC method.
     *
     * @param method the method name, e.g. {@code eth_getCode}
     * @param jsonParams already-JSON-encoded parameters (strings must carry their own quotes)
     * @return the {@code result} node; a JSON null node when the node returns null
     * @throws IllegalStateException if the node returns a JSON-RPC error
     */
    public JsonNode call(final String method, final String... jsonParams) {
        final StringJoiner params = new StringJoiner(",", "[", "]");
        for (final String p : jsonParams) {
            params.add(p);
        }
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":" + ids.getAndIncrement() + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";

        final HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            throw new UncheckedIOException("JSON-RPC " + method + " to " + endpoint + " failed", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during JSON-RPC " + method, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "JSON-RPC " + method + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (final IOException e) {
            throw new UncheckedIOException("unparseable JSON-RPC response for " + method + ": " + response.body(), e);
        }
        final JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("JSON-RPC " + method + " error: " + error);
        }
        final JsonNode result = root.get("result");
        return result == null ? MAPPER.nullNode() : result;
    }

    /**
     * Release the client's selector thread and executor.
     *
     * <p>{@link HttpClient} has been {@link AutoCloseable} since Java 21 and owns a thread pool.
     * Negligible for one environment, but it accumulates across a suite of test classes.
     */
    @Override
    public void close() {
        http.close();
    }

    /** Quote a value as a JSON string parameter. */
    public static String str(final String value) {
        return "\"" + value + "\"";
    }

    /**
     * {@code eth_getCode} at the {@code latest} block.
     *
     * @param address contract address
     * @return the deployed bytecode as {@code 0x}-prefixed hex ({@code "0x"} when there is none)
     */
    public String ethGetCode(final String address) {
        return call("eth_getCode", str(address), str("latest")).asText("0x");
    }

    /**
     * {@code eth_getBlockByNumber} including full transaction bodies.
     *
     * @param blockTag block number as hex, or a tag like {@code latest}
     * @return the block object
     */
    public JsonNode ethGetBlockByNumber(final String blockTag) {
        return call("eth_getBlockByNumber", str(blockTag), "true");
    }

    /** {@code eth_getTransactionReceipt}. */
    public JsonNode ethGetTransactionReceipt(final String txHash) {
        return call("eth_getTransactionReceipt", str(txHash));
    }
}
