// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Reads mined blocks back off a chain so a test can assert on what actually happened, rather than
 * only on what the relay reported.
 *
 * <p>Two questions this exists to answer. "Did anything revert?" — a reverted {@code submitBundle}
 * is mined and paid for, so it is invisible to any liveness check and shows up only in receipts.
 * And "did two senders collide on a nonce?" — the relay serialises submissions per signing account
 * precisely to avoid that, and {@link #nonceHistogram} is the direct evidence: a duplicate nonce
 * means two competing submitters, a gap means a transaction was lost before mining.
 *
 * <p>Block scans are linear and pull full bodies, so keep ranges to the test's own window.
 */
public final class ChainInspector {

    private final RawJsonRpc rpc;

    ChainInspector(final RawJsonRpc rpc) {
        this.rpc = rpc;
    }

    /** @return the current head block number. */
    public long headBlockNumber() {
        return hexToLong(rpc.call("eth_blockNumber").asText("0x0"));
    }

    /**
     * Every transaction mined in {@code [fromBlock, toBlock]}, with receipt status resolved.
     *
     * @param fromBlock inclusive lower bound
     * @param toBlock inclusive upper bound
     * @return the transactions, in block then index order
     */
    public List<TxSummary> transactions(final long fromBlock, final long toBlock) {
        final List<TxSummary> out = new ArrayList<>();
        for (long n = Math.max(0, fromBlock); n <= toBlock; n++) {
            final JsonNode block = rpc.ethGetBlockByNumber("0x" + Long.toHexString(n));
            if (block == null || block.isNull()) {
                continue;
            }
            final JsonNode txs = block.get("transactions");
            if (txs == null || !txs.isArray()) {
                continue;
            }
            for (final JsonNode tx : txs) {
                out.add(toSummary(tx, n));
            }
        }
        return out;
    }

    /**
     * Transactions sent by one address in a block range.
     *
     * @param address sender, matched case-insensitively
     * @param fromBlock inclusive lower bound
     * @param toBlock inclusive upper bound
     * @return the matching transactions
     */
    public List<TxSummary> transactionsFrom(final String address, final long fromBlock, final long toBlock) {
        final String needle = address.toLowerCase(Locale.ROOT);
        return transactions(fromBlock, toBlock).stream()
                .filter(t -> t.from().equals(needle))
                .toList();
    }

    /**
     * Every reverted transaction in a block range. Expected to be empty in a healthy run.
     *
     * <p>{@link TxSummary#revertReason()} is {@code null} here: a receipt does not carry revert data,
     * and recovering it needs an {@code eth_call} replay of each transaction. {@code ChainTx} does that
     * for the calls it issues; a bulk scan deliberately does not.
     *
     * @param fromBlock inclusive lower bound
     * @param toBlock inclusive upper bound
     * @return the reverted transactions
     */
    public List<TxSummary> revertedTransactions(final long fromBlock, final long toBlock) {
        return transactions(fromBlock, toBlock).stream()
                .filter(t -> !t.success())
                .toList();
    }

    /**
     * Nonce to transactions for one sender.
     *
     * <p>A healthy sender yields exactly one transaction per nonce, contiguously. More than one
     * entry for a nonce means two submitters raced the same account; a missing nonce in the middle
     * of the range means a transaction never landed.
     *
     * @param address the sender
     * @param fromBlock inclusive lower bound
     * @param toBlock inclusive upper bound
     * @return nonce to transactions, ascending
     */
    public NavigableMap<Long, List<TxSummary>> nonceHistogram(
            final String address, final long fromBlock, final long toBlock) {
        final NavigableMap<Long, List<TxSummary>> byNonce = new TreeMap<>();
        for (final TxSummary tx : transactionsFrom(address, fromBlock, toBlock)) {
            byNonce.computeIfAbsent(tx.nonce(), k -> new ArrayList<>()).add(tx);
        }
        return byNonce;
    }

    /**
     * Nonces used more than once by one sender — direct evidence of a submitter collision.
     *
     * @param address the sender
     * @param fromBlock inclusive lower bound
     * @param toBlock inclusive upper bound
     * @return the duplicated nonces, ascending
     */
    public List<Long> duplicatedNonces(final String address, final long fromBlock, final long toBlock) {
        return nonceHistogram(address, fromBlock, toBlock).entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    private TxSummary toSummary(final JsonNode tx, final long blockNumber) {
        final String hash = tx.get("hash").asText();
        final JsonNode receipt = rpc.ethGetTransactionReceipt(hash);
        boolean success = false;
        long gasUsed = 0L;
        if (receipt != null && !receipt.isNull()) {
            final JsonNode status = receipt.get("status");
            success = status != null && "0x1".equalsIgnoreCase(status.asText("0x0"));
            final JsonNode gas = receipt.get("gasUsed");
            if (gas != null && !gas.isNull()) {
                gasUsed = hexToLong(gas.asText("0x0"));
            }
        }
        final JsonNode toNode = tx.get("to");
        final String to =
                (toNode == null || toNode.isNull()) ? null : toNode.asText().toLowerCase(Locale.ROOT);
        return new TxSummary(
                hash,
                blockNumber,
                hexToLong(tx.get("nonce").asText("0x0")),
                tx.get("from").asText().toLowerCase(Locale.ROOT),
                to,
                success,
                gasUsed,
                null);
    }

    private static long hexToLong(final String hex) {
        final String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return s.isEmpty() ? 0L : Long.parseUnsignedLong(s, 16);
    }
}
