// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.context.Context;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hiero.clpr.relay.core.FailState;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EvmContractStateReader;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;

/**
 * Polls one {@code ClprService} contract (a {@code (localNetwork, serviceAddress)} pair) for CLPR
 * channels that appear on-chain after the relay has started, registering each newly-discovered
 * channel via {@link ClprServiceHandler#addChannel(Bytes, ProofType)}. Re-registering an
 * already-known channel is a no-op, so discovery is safe to run alongside predefined channels.
 */
final class ChannelDiscoveryTask {

    private static final Logger log = Loggers.getLogger(ChannelDiscoveryTask.class);

    /** keccak256("ChannelCompleted(bytes32,string,bytes,address,bytes32)"), 0x-prefixed. */
    static final String CHANNEL_COMPLETED_TOPIC0 = "0x"
            + AbiCodec.toHexNoPrefix(
                    AbiCodec.Keccak256.keccak256("ChannelCompleted(bytes32,string,bytes,address,bytes32)"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    // Force a full ChannelCompleted log scan every Nth cycle even if channelCount is unchanged.
    private static final int FULL_SCAN_EVERY_CYCLES = 20;

    // Max block span per eth_getLogs request; a wide boot/rescan range is walked in chunks of this
    // size to stay under hosted-provider limits ("block range too large").
    private static final long MAX_SCAN_RANGE_BLOCKS = 10_000L;

    private final String label;
    private final String serviceAddress;
    private final EvmJsonRpcClient rpcClient;
    private final EvmContractStateReader stateReader;
    private final long pollIntervalMs;
    private final String commitmentBlockTag;
    private final Predicate<Bytes> isRegistered;
    /**
     * Resolves a discovered channel's peer proof type from its on-chain peer {@code chainId},
     * returning {@code null} for a peer this relay cannot serve (no {@code chainId → proofType}
     * mapping and not a recognised default) — such channels are skipped rather than mis-wired.
     */
    private final Function<String, ProofType> peerProofTypeResolver;

    /** Registers a newly-discovered channel by id and resolved peer proof type. */
    private final BiConsumer<Bytes, ProofType> register;

    private final FailState failState;
    private final String instanceName;

    private volatile boolean running;
    private volatile Thread thread;

    // Block the ChannelCompleted scan starts from (and the floor a reorg rescan resets to) —
    // typically the ClprService deployment block, to avoid rescanning a large chain from genesis.
    private final long startBlock;

    /** First block not yet scanned for ChannelCompleted logs. */
    private long lastScannedBlock;

    private long lastChannelCount = -1L;
    private int cyclesSinceFullScan = 0;

    ChannelDiscoveryTask(
            final String serviceAddress,
            final EvmJsonRpcClient rpcClient,
            final EvmContractStateReader stateReader,
            final long pollIntervalMs,
            final String commitmentBlockTag,
            final long startBlock,
            final long backoffBaseMs,
            final long backoffCapMs,
            final Predicate<Bytes> isRegistered,
            final Function<String, ProofType> peerProofTypeResolver,
            final BiConsumer<Bytes, ProofType> register,
            final String instanceName) {
        this.serviceAddress = serviceAddress;
        this.rpcClient = rpcClient;
        this.stateReader = stateReader;
        this.pollIntervalMs = pollIntervalMs;
        this.commitmentBlockTag = commitmentBlockTag;
        this.startBlock = startBlock;
        this.lastScannedBlock = startBlock;
        this.isRegistered = isRegistered;
        this.peerProofTypeResolver = peerProofTypeResolver;
        this.register = register;
        this.instanceName = instanceName;
        this.failState = new FailState(backoffBaseMs, backoffCapMs);
        this.label = "discovery " + serviceAddress;
    }

    /** Launch the poll loop on a dedicated virtual thread. No-op if already running. */
    void start() {
        if (running) {
            return;
        }
        running = true;
        Thread.ofVirtual().name(instanceName + " clpr-discovery").start(() -> {
            this.thread = Thread.currentThread();
            if (!instanceName.isBlank()) {
                Context.getThreadLocalContext().add("relay", instanceName);
            }
            Context.getThreadLocalContext().add("discovery", serviceAddress);
            log.info("Started channel discovery for serviceAddress {}", serviceAddress);
            run();
        });
    }

    /** Signal the loop to stop and interrupt any in-flight wait. */
    void stop() {
        running = false;
        final var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void run() {
        while (running) {
            try {
                pollOnce();
                final int cleared = failState.onSuccess();
                if (cleared > 0) {
                    log.info("{} recovered after {} consecutive failure(s)", label, cleared);
                }
                Thread.sleep(pollIntervalMs);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("{} loop interrupted; stopping", label);
                break;
            } catch (final Exception e) {
                if (!running) {
                    break;
                }
                final var decision = failState.onFailure(e);
                if (decision.logFullDetail()) {
                    log.error(
                            "{} failure #{} fp={}: {}",
                            label,
                            decision.consecutiveFailures(),
                            decision.fingerprint(),
                            e.getMessage());
                } else {
                    log.error(
                            "{} failure #{} fp={} (repeat)",
                            label,
                            decision.consecutiveFailures(),
                            decision.fingerprint());
                }
                try {
                    Thread.sleep(decision.backoffMs());
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void pollOnce() {
        final long count = stateReader.readChannelCount(commitmentBlockTag);
        final boolean fullScanDue = cyclesSinceFullScan >= FULL_SCAN_EVERY_CYCLES;
        final long prevCount = lastChannelCount;
        final boolean changed = count != lastChannelCount;
        if (!changed && !fullScanDue) {
            // Nothing to scan this cycle; advance only the idle counter. The change-signal state
            // (lastChannelCount) is committed below, but ONLY after a scan actually succeeds — so
            // a mid-scan RPC failure never swallows a pending change and leaves a new channel
            // undiscovered until the next full-scan window.
            cyclesSinceFullScan++;
            return;
        }

        final long head = rpcClient.ethBlockNumber();
        final long scanFrom = reorgAdjustedScanStart(head);

        final List<Bytes> ids = new ArrayList<>();
        // Walk [scanFrom, head] in bounded chunks so a large boot/rescan range does not exceed
        // hosted-provider eth_getLogs limits. When head < scanFrom (no new blocks since the last
        // scan) the loop body does not run — there is nothing new to fetch.
        for (long from = scanFrom; from <= head; from += MAX_SCAN_RANGE_BLOCKS) {
            final long to = Math.min(from + MAX_SCAN_RANGE_BLOCKS - 1, head);
            final var logs = rpcClient.ethGetLogs(serviceAddress, CHANNEL_COMPLETED_TOPIC0, from, to);
            ids.addAll(EvmJsonRpcClient.decodeChannelCompletedChannelIds(logs));
        }
        // Heartbeat: log a scan at INFO only when it's interesting (channelCount changed or the
        // scan found ChannelCompleted events); the routine idle full-scan is DEBUG so prod logs
        // aren't flooded (one line per ~20 cycles otherwise).
        if (changed || !ids.isEmpty()) {
            log.info(
                    "discovery scan: channelCount={} (prev={}) blocks[{}..{}] changed={} fullScan={} events={}",
                    count,
                    prevCount,
                    scanFrom,
                    head,
                    changed,
                    fullScanDue,
                    ids.size());
        } else {
            log.debug(
                    "discovery scan (idle): channelCount={} blocks[{}..{}] fullScan={}",
                    count,
                    scanFrom,
                    head,
                    fullScanDue);
        }
        for (final Bytes channelId : ids) {
            if (isRegistered.test(channelId)) {
                log.debug("discovery: {} already registered — skipping", channelId);
                continue;
            }
            // Read the on-chain Channel once and apply two skip filters before registering:
            //
            //  1. STATUS: skip channels already CLOSED on-chain. A ChannelCompleted event is
            //     permanent in the logs, so a completed-then-closed channel is re-surfaced by every
            //     boot/full scan. Registering it would spin up sync + monitor tasks that immediately
            //     self-terminate on their first CLOSED read — a wasteful spawn→terminate→respawn churn
            //     each scan window (plus one spurious proof-construction per respawn). Skipping here
            //     honours the original "don't create a task for an already-closed channel" intent;
            //     the tasks' own CLOSED self-termination remains the backstop for a channel that
            //     closes *after* it was registered.
            //
            //  2. PEER PROOF TYPE: resolve the peer's proof type from its on-chain chainId (via the
            //     configured chainId → proofType mapping, with a fallback for EVM peers). A peer this
            //     relay cannot serve — no mapping and not a recognised default, e.g. a Hiero channel
            //     sharing this ClprService — resolves to null and is skipped rather than mis-wired.
            //
            // FAIL-OPEN: if the record can't be read (transient miss / decode issue), the resolver is
            // given a null chainId and falls back to a servable default, so a valid channel is not
            // silently dropped on a transient error.
            ClprChannel record = null;
            try {
                record = stateReader
                        .readChannelState(channelId, commitmentBlockTag)
                        .orElse(null);
            } catch (final RuntimeException e) {
                log.warn(
                        "discovery: could not read on-chain state for {} ({}) — registering anyway (fail-open)",
                        channelId,
                        e.getMessage());
            }
            final String peerChainId = record == null ? null : record.chainId();
            switch (decide(record, peerProofTypeResolver)) {
                case SKIP_CLOSED -> {
                    log.info("discovery: skipping {} — already CLOSED on-chain", channelId);
                    continue;
                }
                case SKIP_UNSERVABLE_PEER -> {
                    log.info(
                            "discovery: skipping {} — no proof type resolvable for peer chainId '{}'"
                                    + " (unmapped, non-EVM peer this relay does not serve)",
                            channelId,
                            peerChainId);
                    continue;
                }
                case REGISTER -> {
                    /* fall through to registration below */
                }
            }
            final ProofType peerProofType = peerProofTypeResolver.apply(peerChainId);
            log.info(
                    "Discovered new channel {} (peer chainId='{}', peerProofType={}) on serviceAddress {}"
                            + " — registering",
                    channelId,
                    peerChainId == null ? "<unread>" : peerChainId,
                    peerProofType,
                    serviceAddress);
            try {
                register.accept(channelId, peerProofType);
            } catch (final RuntimeException e) {
                log.error("Failed to register discovered channel {}: {}", channelId, e.getMessage());
            }
        }
        // The scan (and every log-fetch chunk) succeeded: advance the watermark past the head we
        // just queried and commit the change-signal state. Committing here — rather than before the
        // fetch — means a mid-scan RPC failure is retried on the next cycle instead of being lost.
        lastScannedBlock = head + 1;
        lastChannelCount = count;
        cyclesSinceFullScan = 0;
    }

    /** Outcome of the per-discovered-channel registration filter. */
    enum Decision {
        /** Register: not CLOSED and the peer's proof type resolves (includes fail-open null record). */
        REGISTER,
        /** Skip: the channel is already CLOSED on-chain (a re-surfaced ChannelCompleted event). */
        SKIP_CLOSED,
        /** Skip: no proof type resolvable for the peer chain — a peer this relay cannot serve. */
        SKIP_UNSERVABLE_PEER
    }

    /**
     * Registration decision for a discovered channel: {@link Decision#SKIP_CLOSED} when the record
     * is CLOSED, {@link Decision#SKIP_UNSERVABLE_PEER} when {@code peerProofTypeResolver} yields no
     * proof type, otherwise {@link Decision#REGISTER}.
     *
     * @param record                the on-chain {@link ClprChannel}, or {@code null} if unreadable
     * @param peerProofTypeResolver  peer {@code chainId → ProofType}; a {@code null} result ⇒ skip
     * @return the registration decision
     */
    static Decision decide(final ClprChannel record, final Function<String, ProofType> peerProofTypeResolver) {
        if (record != null && record.status() == ClprChannelStatus.CLOSED) {
            return Decision.SKIP_CLOSED;
        }
        final String peerChainId = record == null ? null : record.chainId();
        return peerProofTypeResolver.apply(peerChainId) == null ? Decision.SKIP_UNSERVABLE_PEER : Decision.REGISTER;
    }

    // First block to scan this cycle. A head strictly below the last scanned block (reorg or a
    // fresh/rewound node) resets the watermark to the configured start block for a full rescan.
    private long reorgAdjustedScanStart(final long head) {
        if (head + 1 < lastScannedBlock) {
            log.info(
                    "{}: head {} is below the last scanned block {} — reorg or fresh node, rescanning from block {}",
                    label,
                    head,
                    lastScannedBlock - 1,
                    startBlock);
            lastScannedBlock = startBlock;
        }
        return lastScannedBlock;
    }
}
