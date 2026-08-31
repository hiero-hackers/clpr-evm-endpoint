// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.test.harness.ChainTxSubmitter;
import org.jspecify.annotations.Nullable;

/**
 * Sends a transaction and waits for a <em>successful</em> receipt.
 *
 * <p>The distinction matters more than it looks: a reverted transaction is mined and charged exactly
 * like a successful one, so code that only waits for "a receipt" happily proceeds after a failed
 * setup step and fails later somewhere unrelated. Every call here either lands or throws with the
 * receipt attached.
 */
public final class ChainTx {

    /**
     * Gas limit for framework-issued calls.
     *
     * <p>Deliberately below the EIP-7825 cap of 2^24 that Osaka would impose — the E2E genesis leaves
     * Osaka off precisely because inbound bundles can exceed it, but nothing the framework itself sends
     * (registrations, deploys, sends, transfers) comes close, and the relay's own {@code submitBundle}
     * goes through its own submitter rather than this class.
     */
    public static final long DEFAULT_GAS_LIMIT = 12_000_000L;

    /** Receipt polling: 240 x 250 ms = 60 s, comfortably above the 1 s block period. */
    public static final int RECEIPT_POLL_ATTEMPTS = 240;

    public static final long RECEIPT_POLL_INTERVAL_MS = 250L;

    /**
     * Largest value a single transaction can carry.
     *
     * <p>{@code ChainTxSubmitter.sendRawTx} and {@code EthSigner.signEip1559Transaction} both take
     * {@code valueWei} as a {@code long}, so this is a hard ceiling on any transfer the framework
     * makes — and therefore on any balance a fault can sweep. The genesis balance is chosen to stay
     * under it; see {@code BesuQbftGenesis.DEFAULT_BALANCE_WEI}.
     */
    public static final BigInteger MAX_TRANSFERABLE_WEI = BigInteger.valueOf(Long.MAX_VALUE);

    private ChainTx() {}

    /**
     * Send a contract call and wait for it to succeed.
     *
     * @param rpc JSON-RPC client for receipt polling
     * @param submitter client-side signer for the sending account
     * @param to target contract
     * @param callData ABI-encoded call data
     * @param valueWei value to attach, {@code 0} for a plain call
     * @param what short description used in error messages
     * @return the receipt
     */
    public static JsonNode sendAndAwait(
            final EvmJsonRpcClient rpc,
            final ChainTxSubmitter submitter,
            final String to,
            final byte[] callData,
            final BigInteger valueWei,
            final String what) {
        return sendAndAwait(rpc, null, submitter, to, callData, valueWei, what);
    }

    /**
     * Send a contract call and wait for it to succeed, replaying a revert to recover its reason.
     *
     * <p>A receipt records only {@code status: 0x0}; the revert data is not in it. Replaying the same
     * call with {@code eth_call} at the block it was mined in returns that data, which for a CLPR
     * custom error is the difference between "completeChannel reverted" and "the verifier rejected the
     * trust anchor". Worth the extra round trip on a path that only runs when something is broken.
     *
     * @param rpc JSON-RPC client for receipt polling
     * @param raw raw JSON-RPC caller used to replay a revert; may be {@code null} to skip the replay
     * @param submitter client-side signer for the sending account
     * @param to target contract
     * @param callData ABI-encoded call data
     * @param valueWei value to attach
     * @param what short description used in error messages
     * @return the receipt
     */
    public static JsonNode sendAndAwait(
            final EvmJsonRpcClient rpc,
            @Nullable final RawJsonRpc raw,
            final ChainTxSubmitter submitter,
            final String to,
            final byte[] callData,
            final BigInteger valueWei,
            final String what) {
        final String txHash = submitter.sendRawTx(to, callData, requireTransferable(valueWei, what), DEFAULT_GAS_LIMIT);
        for (int attempt = 0; attempt < RECEIPT_POLL_ATTEMPTS; attempt++) {
            final JsonNode receipt = rpc.ethGetTransactionReceipt(txHash);
            if (receipt != null && !receipt.isNull()) {
                final JsonNode status = receipt.get("status");
                final String value = status == null || status.isNull() ? "0x0" : status.asText();
                if ("0x1".equalsIgnoreCase(value)) {
                    return receipt;
                }
                throw new IllegalStateException(what + " reverted (tx " + txHash + ")"
                        + replayReason(raw, receipt, submitter.address(), to, callData) + ", receipt=" + receipt);
            }
            sleep();
        }
        throw new IllegalStateException("no receipt for " + what + " (tx " + txHash + ") after "
                + (RECEIPT_POLL_ATTEMPTS * RECEIPT_POLL_INTERVAL_MS / 1000) + "s");
    }

    /**
     * Replay a reverted call to recover its revert data, and name the custom error when recognised.
     *
     * @return a human-readable suffix, or an empty string when the reason cannot be recovered
     */
    private static String replayReason(
            @Nullable final RawJsonRpc raw,
            final JsonNode receipt,
            final String from,
            final String to,
            final byte[] callData) {
        if (raw == null) {
            return "";
        }
        try {
            final JsonNode blockNumber = receipt.get("blockNumber");
            final String blockTag = blockNumber == null || blockNumber.isNull() ? "latest" : blockNumber.asText();
            raw.call(
                    "eth_call",
                    "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"data\":\"" + AbiCodec.toHex(callData) + "\"}",
                    RawJsonRpc.str(blockTag));
            // The replay succeeded, so the failure was state- or gas-dependent rather than a
            // deterministic revert — say so instead of implying no reason exists.
            return " (replay at the mined block did not revert; the failure depends on state or gas)";
        } catch (final RuntimeException e) {
            final String message = e.getMessage() == null ? "" : e.getMessage();
            return " — revert data: " + message + describeSelector(message);
        }
    }

    /** Name a CLPR custom error from the 4-byte selector embedded in the revert data. */
    private static String describeSelector(final String revertData) {
        for (final String signature : KNOWN_ERRORS) {
            final String selector = AbiCodec.toHexNoPrefix(AbiCodec.functionSelector(signature));
            if (revertData.toLowerCase(java.util.Locale.ROOT).contains(selector)) {
                return " [" + signature + "]";
            }
        }
        return "";
    }

    /**
     * Custom errors worth naming. Mostly the QBFT verifier's, because those are the ones a
     * hand-assembled config proof trips over, and their selectors are otherwise unreadable hex.
     */
    private static final java.util.List<String> KNOWN_ERRORS = java.util.List.of(
            "InvalidTrustAnchor()",
            "InvalidPayloadShape()",
            "InvalidProofPayloadShape()",
            "InvalidHeader()",
            "InvalidConfigHeader()",
            "InvalidConfigThrottleFieldCount()",
            "InvalidTrustAnchorFields()",
            "EpochMismatch()",
            "EmptyValidator()",
            "MultiValidatorNotSupported()",
            "InvalidNumberOfSealSignatures()",
            "ClprDisabled()",
            "ClprInvalidEndpointAddress()",
            "ClprEndpointNotRegistered()",
            "ClprChannelNotFound()",
            "ClprConnectorNotFound()",
            "ClprInvalidSignature()",
            "ClprInvalidCommitment()",
            "ClprQueueFull()");

    /**
     * Transfer ETH and wait for the transfer to succeed.
     *
     * @param rpc JSON-RPC client
     * @param submitter client-side signer
     * @param to recipient
     * @param valueWei amount
     * @param what short description for error messages
     * @return the receipt
     */
    public static JsonNode fund(
            final EvmJsonRpcClient rpc,
            final ChainTxSubmitter submitter,
            final String to,
            final BigInteger valueWei,
            final String what) {
        return sendAndAwait(rpc, submitter, to, new byte[0], valueWei, what);
    }

    /**
     * Poll until a receipt appears, then require status {@code 0x1}.
     *
     * @param rpc JSON-RPC client
     * @param txHash the transaction hash
     * @param what short description for error messages
     * @return the receipt
     */
    public static JsonNode awaitSuccess(final EvmJsonRpcClient rpc, final String txHash, final String what) {
        for (int attempt = 0; attempt < RECEIPT_POLL_ATTEMPTS; attempt++) {
            final JsonNode receipt = rpc.ethGetTransactionReceipt(txHash);
            if (receipt != null && !receipt.isNull()) {
                final JsonNode status = receipt.get("status");
                final String value = status == null || status.isNull() ? "0x0" : status.asText();
                if ("0x1".equalsIgnoreCase(value)) {
                    return receipt;
                }
                throw new IllegalStateException(what + " reverted (tx " + txHash + "), receipt=" + receipt);
            }
            sleep();
        }
        throw new IllegalStateException("no receipt for " + what + " (tx " + txHash + ") after "
                + (RECEIPT_POLL_ATTEMPTS * RECEIPT_POLL_INTERVAL_MS / 1000) + "s");
    }

    /**
     * Reject a value a single transaction cannot carry, naming the reason.
     *
     * <p>Without this the failure is a bare {@link ArithmeticException} from {@code longValueExact()},
     * which says nothing about the {@code long}-typed submitter API being the actual constraint.
     */
    private static long requireTransferable(final BigInteger valueWei, final String what) {
        if (valueWei.signum() < 0) {
            throw new IllegalArgumentException(what + ": value must not be negative, got " + valueWei);
        }
        if (valueWei.compareTo(MAX_TRANSFERABLE_WEI) > 0) {
            throw new IllegalArgumentException(what + ": " + valueWei
                    + " wei exceeds what one transaction can carry (" + MAX_TRANSFERABLE_WEI
                    + "). sendRawTx takes valueWei as a long, so transfers are capped there.");
        }
        return valueWei.longValueExact();
    }

    private static void sleep() {
        try {
            Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling for a receipt", e);
        }
    }

    /**
     * Read a {@code bytes32} return value from a view-style call.
     *
     * @param rpc JSON-RPC client
     * @param to target contract
     * @param callData ABI-encoded call data
     * @return the 32-byte result
     */
    public static byte[] callBytes32(final EvmJsonRpcClient rpc, final String to, final byte[] callData) {
        final byte[] decoded = AbiCodec.fromHex(rpc.ethCall(to, AbiCodec.toHex(callData), "latest"));
        if (decoded.length < 32) {
            throw new IllegalStateException("expected 32 bytes from " + to + ", got " + decoded.length);
        }
        return AbiCodec.decodeBytes32(decoded, 0);
    }

    /** @return the {@code to} field of a receipt, or {@code null} for a contract creation. */
    @Nullable
    public static String receiptTo(final JsonNode receipt) {
        final JsonNode to = receipt.get("to");
        return to == null || to.isNull() ? null : to.asText();
    }
}
