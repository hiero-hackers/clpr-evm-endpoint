// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Metrics;
import java.math.BigInteger;
import java.util.HexFormat;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.FailState;
import org.hiero.clpr.relay.core.PeerEndpointCache;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.metrics.LabeledCounter;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.jspecify.annotations.Nullable;

/**
 * Per-channel poller that observes a single channel's on-ledger {@link ClprChannel}
 * record and reacts to changes in it. Intended to run on a virtual thread.
 *
 * <p>The {@code ClprChannel} record is the channel's full on-ledger state: its queue and
 * lifecycle fields (message ids, {@code status}, trust anchor) and its manifest-version marker
 * ({@code endpointManifestVersion}). Each cycle reads the record at the configured commitment
 * level; when it changes, a queue or lifecycle advance notifies a {@link BundleConstructor}, and
 * a manifest-version advance refreshes the channel's {@link PeerEndpointCache} from the
 * authoritative on-ledger peer endpoint manifest (replace, not merge). Between cycles the task
 * sleeps for a fixed polling interval.
 *
 * <p>The loop terminates when {@link #stop()} is called or the channel transitions to
 * {@link ClprChannelStatus#CLOSED}.
 */
public class EvmChannelStateChangeTask implements Runnable {
    private static final Logger log = Loggers.getLogger(EvmChannelStateChangeTask.class);

    private final Bytes channelId;
    private final CommitmentLevel commitmentLevel;
    private final EvmJsonRpcClient evmJsonRpcClient;
    private final ContractStateReader stateReader;
    private final BundleConstructor proofConstructor;
    private final PeerEndpointCache peerCache;
    private final PeerEndpointTlsRegistry tlsRegistry;
    /** Interval in milliseconds between successive state reads. */
    private final long pollIntervalMs;
    /**
     * Number of blocks to read BEHIND the commitment-level head before building a bundle. Zero for
     * QBFT (the bundle is anchored to the block being read). Non-zero for CometBFT, whose proof is
     * anchored to the signed header at H+1 (and validator set at H+2): reading at the head means
     * those headers are not committed yet (CometBFT rejects a future height), so the relay reads at
     * head-lag to keep the EVM state read and the CometBFT signed-header / ICS-23 proofs at the
     * same, already-committed height.
     */
    private final int proofLagBlocks;

    private final String channelIdLabel;
    private final LabeledCounter pollFailed;
    private final LabeledCounter manifestRefreshed;
    private final LabeledCounter trustAnchorChanges;
    private final FailState failState;

    @Nullable
    private volatile Thread thread;
    /** The last channel state observed by this task, or {@code null} before the first read. */
    @Nullable
    private ClprChannel lastKnownState;

    /**
     * Flag used to request graceful shutdown of the monitoring loop.
     * Declared {@code volatile} so writes are visible across virtual threads.
     */
    private volatile boolean running = true;

    /**
     * Create a new {@code EvmChannelStateChangeTask} for a single channel.
     *
     * @param channelId     the CLPR channel to monitor
     * @param commitmentLevel  the finality level at which to read on-chain state
     * @param evmJsonRpcClient resolves the block to read against for the commitment level
     * @param stateReader      reads CLPR state from the on-chain contract
     * @param proofConstructor notified whenever the queue or lifecycle state of the monitored
     *                         channel changes
     * @param peerCache        the channel's peer endpoint cache, replaced from the on-ledger
     *                         peer manifest whenever the manifest-version marker advances
     * @param tlsRegistry      global TLS registry updated in parallel with the peer cache on every
     *                         manifest change so the mTLS trust manager stays current
     * @param metrics          registry for the {@code evm.listener.poll.failed} and
     *                         {@code evm.listener.manifest.refreshed} counters (labeled by
     *                         {@code channel_id})
     * @param pollIntervalMs   interval in ms between successive state reads
     * @param backoffBaseMs    first-failure backoff in ms (doubled per consecutive failure)
     * @param backoffCapMs     ceiling on the per-failure backoff in ms
     * @param proofLagBlocks   blocks to read behind the commitment head (0 for QBFT; &gt;0 for
     *                         CometBFT, see {@link #proofLagBlocks})
     */
    public EvmChannelStateChangeTask(
            final Bytes channelId,
            final CommitmentLevel commitmentLevel,
            final EvmJsonRpcClient evmJsonRpcClient,
            final ContractStateReader stateReader,
            final BundleConstructor proofConstructor,
            final PeerEndpointCache peerCache,
            final PeerEndpointTlsRegistry tlsRegistry,
            final Metrics metrics,
            final long pollIntervalMs,
            final long backoffBaseMs,
            final long backoffCapMs,
            final int proofLagBlocks) {
        this.channelId = channelId;
        this.commitmentLevel = commitmentLevel;
        this.evmJsonRpcClient = evmJsonRpcClient;
        this.stateReader = stateReader;
        this.proofConstructor = proofConstructor;
        this.peerCache = peerCache;
        this.tlsRegistry = tlsRegistry;
        this.pollIntervalMs = pollIntervalMs;
        this.proofLagBlocks = proofLagBlocks;
        this.channelIdLabel = HexFormat.of().formatHex(channelId.toByteArray());
        this.pollFailed = new LabeledCounter(
                "evm.listener", "poll.failed", "Listener poll iterations that threw an exception", metrics);
        this.manifestRefreshed = new LabeledCounter(
                "evm.listener",
                "manifest.refreshed",
                "Peer manifest cache refreshes applied after an on-ledger manifest-version advance",
                metrics);
        this.trustAnchorChanges = new LabeledCounter(
                "contract", "trust_anchor.changes", "Times the trust anchor bytes changed for this channel", metrics);
        this.failState = new FailState(backoffBaseMs, backoffCapMs);
    }

    /**
     * Run the polling loop. Designed to be called on a virtual thread.
     *
     * <p>On each iteration the loop:
     * <ol>
     *   <li>Reads the current {@link ClprChannel} state, skipping the tick if no record exists yet.</li>
     *   <li>Compares it with the last known state via {@link #hasStateChanged}.</li>
     *   <li>If changed (or on the first read), fetches queued messages and notifies
     *       the {@link BundleConstructor}.</li>
     *   <li>Stops if the channel is {@link ClprChannelStatus#CLOSED}.</li>
     *   <li>Sleeps for the configured poll interval before repeating.</li>
     * </ol>
     *
     * <p>Any unchecked exception from a poll is caught and routed through {@link FailState}: the
     * loop logs at ERROR, backs off (bounded, capped — never the poll-interval
     * cadence), and continues, so the monitor neither dies silently nor tight-loops on a persistent
     * fault; the first clean poll resets the backoff. An interrupt — from a cadence sleep or
     * mid-backoff — breaks the loop.
     */
    @Override
    public void run() {
        this.thread = Thread.currentThread();
        while (running) {
            try {
                pollOnce();
                final int cleared = failState.onSuccess();
                if (cleared > 0) {
                    log.info("recovered after {} consecutive failure(s)", cleared);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("poll loop interrupted; stopping");
                break;
            } catch (final Exception e) {
                if (!running) {
                    // Shutting down: a poll that fails because the JSON-RPC client was interrupted
                    // or its endpoint went away is expected teardown, not a fault. Exit quietly
                    // rather than logging at ERROR or counting it as a poll failure.
                    break;
                }
                pollFailed.increment("channel_id", channelIdLabel);
                final var decision = failState.onFailure(e);
                if (decision.logFullDetail()) {
                    log.error(
                            "failure #{} fp={}: {}",
                            e,
                            decision.consecutiveFailures(),
                            decision.fingerprint(),
                            e.getMessage());
                } else {
                    log.error("failure #{} fp={} (repeat)", decision.consecutiveFailures(), decision.fingerprint());
                }
                try {
                    //noinspection BusyWait — periodic poll cadence;
                    Thread.sleep(decision.backoffMs());
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Execute a single poll: read the current channel state, notify the {@link BundleConstructor}
     * on a change, self-stop on {@code CLOSED}, and sleep the success-path cadence.
     *
     * @throws InterruptedException if the thread is interrupted during the cadence sleep
     */
    private void pollOnce() throws InterruptedException {
        final var headNumber = evmJsonRpcClient
                .ethGetBlockHeaderByNumber(commitmentLevel.toBlockTag())
                .number();
        // Read `proofLagBlocks` behind the head so a CometBFT bundle's signed header (H+1) and
        // validator set (H+2) are already committed. Zero-lag (QBFT) reads the head as before.
        final var blockNumber = headNumber.subtract(BigInteger.valueOf(proofLagBlocks));
        if (blockNumber.signum() < 0) {
            return; // chain younger than the configured proof lag — nothing provable yet
        }
        final var blockNumberHex = "0x" + blockNumber.toString(16);

        // Empty means no on-chain Channel record yet (PENDING or unregistered). Skip
        // this tick rather than synthesizing a default state that would tell the
        // ProofConstructor to cache a misleading snapshot.
        final var currentStateOpt = stateReader.readChannelState(channelId, blockNumberHex);
        if (currentStateOpt.isEmpty()) {
            log.debug("no Channel record yet — skipping tick");
            Thread.sleep(pollIntervalMs);
            return;
        }
        final ClprChannel currentState = currentStateOpt.get();
        final ClprChannel previousState = lastKnownState;

        if (previousState != null && !currentState.trustAnchor().equals(previousState.trustAnchor())) {
            trustAnchorChanges.increment("channel_id", channelIdLabel);
            log.info("trust anchor changed: old={} new={}", previousState.trustAnchor(), currentState.trustAnchor());
        }

        // Per-poll diagnostic at DEBUG so prod logs aren't flooded by steady-state ticks.
        log.debug(
                "status={} next={} acked={} recv={}",
                currentState.status(),
                currentState.nextMessageId(),
                currentState.ackedMessageId(),
                currentState.receivedMessageId());
        if (previousState == null || hasStateChanged(previousState, currentState)) {
            final var messages = stateReader.readQueuedMessages(
                    channelId, currentState.ackedMessageId() + 1, currentState.nextMessageId(), blockNumberHex);

            // Real state transition — meaningful event, keep at INFO.
            log.info(
                    "state changed for block={}; {} pending message(s) (range [{}..{}], acked={}, recv={})",
                    blockNumber,
                    messages.size(),
                    currentState.ackedMessageId() + 1,
                    currentState.nextMessageId() - 1,
                    currentState.ackedMessageId(),
                    currentState.receivedMessageId());
            if (log.isDebugEnabled()) {
                for (final var msg : messages) {
                    final var payload = msg.value().payload();
                    final var kind =
                            payload == null ? "unset" : payload.payload().kind().name();
                    log.debug(
                            "new msg read from state id={} kind={} runningHash={}",
                            msg.messageId(),
                            kind,
                            msg.value().runningHashAfterProcessing());
                }
            }
            proofConstructor.onStateChanged(blockNumber, channelId, currentState, messages);
        }

        // Manifest-version branch — refresh the peer endpoint cache when the on-ledger manifest
        // version advances. Independent of the queue branch above: a queue-only advance triggers no
        // manifest re-read, and a manifest-only advance does not re-notify the proof constructor.
        // Also refreshes on first observation, which repopulates a channel whose cache was empty
        // at startup.
        if (previousState == null || manifestVersionChanged(previousState, currentState)) {
            refreshPeerManifest(previousState, currentState, blockNumberHex);
        }

        // Record the fully observed state so the next tick compares against it, regardless of which
        // branch (if any) fired above.
        lastKnownState = currentState;

        // CLOSED: terminate the task. Continuing to poll a closed channel makes no sense.
        if (currentState.status() == ClprChannelStatus.CLOSED) {
            log.info("CLOSED, stopping monitor");
            running = false;
            return;
        }

        Thread.sleep(pollIntervalMs);
    }

    /**
     * Request the polling loop to stop after the current iteration completes.
     * Running this method guarantees the loop exits; the interrupt only shortens the wait.
     */
    public void stop() {
        running = false;
        final var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Returns {@code true} if the polling loop is still running.
     *
     * @return whether the task is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the channel identifier monitored by this task.
     *
     * @return the channel ID
     */
    public Bytes channelId() {
        return channelId;
    }

    /**
     * Returns this loop's failure state machine.
     *
     * @return the {@link FailState}
     */
    public FailState failState() {
        return failState;
    }

    /**
     * Returns {@code true} when any of the queue counter fields differ between the two states.
     *
     * @param previous the previously observed channel state
     * @param current  the most recently observed channel state
     * @return {@code true} if the queue has advanced or an acknowledgement has changed
     */
    private static boolean hasStateChanged(final ClprChannel previous, final ClprChannel current) {
        return previous.nextMessageId() != current.nextMessageId()
                || previous.ackedMessageId() != current.ackedMessageId()
                || previous.receivedMessageId() != current.receivedMessageId()
                // Status can change without any counter moving (e.g. admin closeChannel flips
                // ACTIVE -> CLOSING). Without this, the cached proof would keep its stale status
                // and the lifecycle change would never reach the peer.
                || previous.status() != current.status();
    }

    /**
     * Returns {@code true} when the channel's manifest-version marker differs between the two
     * states, signaling that the on-ledger peer endpoint manifest has been replaced.
     *
     * @param previous the previously observed channel state
     * @param current  the most recently observed channel state
     * @return {@code true} if {@code endpointManifestVersion} changed
     */
    private static boolean manifestVersionChanged(final ClprChannel previous, final ClprChannel current) {
        return previous.endpointManifestVersion() != current.endpointManifestVersion();
    }

    /**
     * Re-read the authoritative on-ledger peer endpoint manifest and replace the channel's
     * {@link PeerEndpointCache} with it (replace, not merge), logging the change at {@code INFO}
     * and an empty result at {@code WARN}.
     *
     * @param previous the previously observed channel state, or {@code null} on first observation
     * @param current  the channel state whose manifest-version advance triggered the refresh
     * @param blockTag the block the triggering {@code current} state was read at, so the manifest is
     *                 read from the same block rather than a tag that may resolve to a later one
     */
    private void refreshPeerManifest(
            @Nullable final ClprChannel previous, final ClprChannel current, final String blockTag) {
        if (previous == null) {
            log.info("initial manifest sync version={}", current.endpointManifestVersion());
        } else {
            log.info(
                    "manifest version advanced {} -> {}; refreshing cache",
                    previous.endpointManifestVersion(),
                    current.endpointManifestVersion());
        }

        final ClprEndpointManifest manifest = stateReader.readPeerEndpointManifest(channelId, blockTag);
        // The manifest endpoints carry the on-chain TLS certs, so one read feeds both the peer cache
        // and the mTLS trust registry (replace, not merge).
        peerCache.replaceAll(manifest.endpoints());
        tlsRegistry.update(channelId, manifest.endpoints());

        if (manifest.endpoints().isEmpty()) {
            log.warn("peer manifest is now empty after refresh; sync will stall until it is repopulated");
            return;
        }
        log.info(
                "manifest cache refreshed: {} endpoint(s) (version={})",
                manifest.endpoints().size(),
                manifest.version());
        manifestRefreshed.increment("channel_id", channelIdLabel);
    }
}
