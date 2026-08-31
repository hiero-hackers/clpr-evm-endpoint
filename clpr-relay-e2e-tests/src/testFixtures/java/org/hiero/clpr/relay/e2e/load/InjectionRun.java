// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.load;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.hiero.clpr.relay.e2e.chain.TxSummary;
import org.hiero.clpr.relay.e2e.channel.ClprAbi;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;

/**
 * The outcome of one batch of injected messages: what was accepted, what landed, what reverted, and
 * which message ids the chain assigned.
 *
 * <p>Keeps the three counts apart on purpose. "Accepted by the node" only means the transaction
 * entered the txpool. "Confirmed" means it mined successfully. "Reverted" means it mined and failed —
 * charged like a success and invisible to anything that merely waits for a receipt. Conflating them
 * is how a broken send side gets mistaken for a broken relay.
 */
public final class InjectionRun {

    private final EvmJsonRpcClient rpc;
    private final byte[] channelId;
    private final List<String> submittedHashes = new ArrayList<>();
    private final Instant startedAt = Instant.now();

    private final List<TxSummary> reverted = new ArrayList<>();
    private final Map<String, Integer> errorHistogram = new LinkedHashMap<>();
    private final NavigableSet<Long> messageIds = new TreeSet<>();

    private int confirmed;
    private boolean reconciled;
    private Instant finishedAt = Instant.now();

    InjectionRun(final EvmJsonRpcClient rpc, final byte[] channelId) {
        this.rpc = rpc;
        this.channelId = channelId.clone();
    }

    void recordSubmitted(final String txHash) {
        submittedHashes.add(txHash);
    }

    void recordRejected(final String reason) {
        errorHistogram.merge(reason, 1, Integer::sum);
    }

    /** @return transactions the node accepted into its pool. */
    public int submitted() {
        return submittedHashes.size();
    }

    /** @return transactions that mined successfully. Requires {@link #awaitAllConfirmed}. */
    public int confirmed() {
        requireReconciled();
        return confirmed;
    }

    /** @return transactions that mined and reverted. Expected to be zero. */
    public int reverted() {
        requireReconciled();
        return reverted.size();
    }

    /** @return the reverted transactions. */
    public List<TxSummary> revertedTransactions() {
        requireReconciled();
        return List.copyOf(reverted);
    }

    /** @return send-side rejections (never entered the pool) by reason. */
    public Map<String, Integer> errorHistogram() {
        return Map.copyOf(errorHistogram);
    }

    /**
     * The message ids the service assigned, read from {@code MessageQueued} events.
     *
     * @return the assigned ids, ascending
     */
    public NavigableSet<Long> messageIds() {
        requireReconciled();
        return new TreeSet<>(messageIds);
    }

    /** @return wall-clock time from the first send to the last confirmation. */
    public Duration wallClock() {
        return Duration.between(startedAt, finishedAt);
    }

    /** @return confirmed messages per second over {@link #wallClock()}. */
    public double effectiveRatePerSecond() {
        final double seconds = wallClock().toMillis() / 1000.0;
        return seconds <= 0 ? 0 : confirmed() / seconds;
    }

    /**
     * Poll every submitted transaction to a receipt, classifying each and collecting the message ids.
     *
     * @param timeout how long to wait for the whole batch
     */
    public void awaitAllConfirmed(final Duration timeout) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        final List<String> pending = new ArrayList<>(submittedHashes);
        confirmed = 0;
        reverted.clear();
        messageIds.clear();

        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            final List<String> stillPending = new ArrayList<>();
            for (final String hash : pending) {
                final JsonNode receipt = rpc.ethGetTransactionReceipt(hash);
                if (receipt == null || receipt.isNull()) {
                    stillPending.add(hash);
                    continue;
                }
                classify(hash, receipt);
            }
            pending.clear();
            pending.addAll(stillPending);
            if (!pending.isEmpty()) {
                sleep();
            }
        }
        finishedAt = Instant.now();
        reconciled = true;
        if (!pending.isEmpty()) {
            throw new AssertionError("only " + (submittedHashes.size() - pending.size()) + " of "
                    + submittedHashes.size() + " injected transactions were mined within " + timeout
                    + "; " + pending.size() + " never appeared. A transaction accepted into the pool but"
                    + " never mined usually means the per-sender pool limit was exceeded.");
        }
    }

    private void classify(final String hash, final JsonNode receipt) {
        final JsonNode status = receipt.get("status");
        final boolean ok = status != null && "0x1".equalsIgnoreCase(status.asText("0x0"));
        if (!ok) {
            reverted.add(new TxSummary(
                    hash,
                    hexToLong(text(receipt, "blockNumber")),
                    0L,
                    text(receipt, "from"),
                    text(receipt, "to"),
                    false,
                    hexToLong(text(receipt, "gasUsed")),
                    null));
            errorHistogram.merge("reverted", 1, Integer::sum);
            return;
        }
        confirmed++;
        collectMessageIds(receipt);
    }

    /**
     * Extract the assigned message id from the receipt's {@code MessageQueued} log.
     *
     * <p>Filtered by channel id (topic 1) so a receipt carrying events for several channels — which a
     * multi-channel test produces — cannot contribute another channel's ids.
     */
    private void collectMessageIds(final JsonNode receipt) {
        final JsonNode logs = receipt.get("logs");
        if (logs == null || !logs.isArray()) {
            return;
        }
        final String wantTopic = AbiCodec.toHex(ClprAbi.messageQueuedTopic());
        final String wantChannel = AbiCodec.toHex(channelId);
        for (final JsonNode log : logs) {
            final JsonNode topics = log.get("topics");
            if (topics == null || !topics.isArray() || topics.size() < 2) {
                continue;
            }
            if (!wantTopic.equalsIgnoreCase(topics.get(0).asText())
                    || !wantChannel.equalsIgnoreCase(topics.get(1).asText())) {
                continue;
            }
            // Non-indexed data: messageId (uint64, right-aligned in word 0) then messageType.
            final byte[] data = AbiCodec.fromHex(log.get("data").asText("0x"));
            if (data.length >= 32) {
                messageIds.add(AbiCodec.decodeUint64(data, 0));
            }
        }
    }

    private void requireReconciled() {
        if (!reconciled) {
            throw new IllegalStateException(
                    "call awaitAllConfirmed(...) first — until the receipts are read, only submitted() is known");
        }
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static long hexToLong(final String hex) {
        final String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return s.isEmpty() ? 0L : Long.parseUnsignedLong(s, 16);
    }

    private static void sleep() {
        try {
            Thread.sleep(250L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reconciling injected transactions", e);
        }
    }

    @Override
    public String toString() {
        return "InjectionRun[submitted=" + submitted() + (reconciled ? ", confirmed=" + confirmed : "")
                + (reconciled ? ", reverted=" + reverted.size() : "") + ", errors=" + errorHistogram + "]";
    }
}
