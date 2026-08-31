// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.sync;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import java.util.HexFormat;
import java.util.Objects;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.BundleLog;
import org.hiero.clpr.relay.core.BundlePayloadCodec;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.FailState;
import org.hiero.clpr.relay.core.PeerManifestVersionCache;
import org.hiero.clpr.relay.core.TransactionSubmitter;
import org.hiero.clpr.relay.core.metrics.LabeledCounter;
import org.hiero.clpr.relay.core.metrics.MetricLabels;
import org.hiero.clpr.relay.grpc.client.ClprEndpointClient;
import org.jspecify.annotations.Nullable;

/**
 * Per-channel sync loop intended to run on a virtual thread.
 *
 * <p>Each cycle reads the current channel state from the chain, checks whether there are
 * outbound messages to deliver, fetches a cached proof, selects a peer, and
 * dispatches the sync request via {@link ClprEndpointClient}. Between cycles the task sleeps for a
 * configurable interval derived from the throttle settings.
 */
public class ChannelSyncTask implements Runnable {
    /** Default sync interval when not explicitly configured (ms). */
    public static final long DEFAULT_INTERVAL_MS = 1_000L;

    private static final Logger log = Loggers.getLogger(ChannelSyncTask.class);
    // Unique ID for this channel. Must have a matching ID on the source & destination ledgers
    private final Bytes channelId;
    // EVM chains have different commitment levels with different "finality" guarantees
    private final CommitmentLevel commitmentLevel;
    // Reads state from the EVM chain for the channel
    private final ContractStateReader stateReader;
    // Creates a state proof to send to the destination
    private final BundleConstructor bundleConstructor;
    // Decodes (and replay-trims) the remote peer's bundle proofs before submission
    private final BundlePayloadCodec proofCodec;
    // Submits verified bundles from the remote peer into this ledger
    private final TransactionSubmitter txSubmitter;
    // Determines which remote peer to sync with
    private final PeerSelector peerSelector;
    // Used to talk with the remote peer
    private final ClprEndpointClient clprEndpointClient;
    // Records the manifest version the peer reports holding of our manifest (outbound leg)
    private final PeerManifestVersionCache peerManifestVersions;

    // interval between sync cycles (ms) based on config
    private final long syncIntervalMs;
    // Hex label for this channel — computed once, reused in all labeled metrics.
    private final String channelIdLabel;
    private final Counter cyclesTotal;
    private final LabeledCounter cyclesFailed;
    private final Counter noPeerSkips;
    private final Counter noProofSkips;
    private final LabeledCounter peerSyncSuccess;
    private final LabeledCounter peerSyncErrors;
    private final LabeledCounter outboundAttempts;
    private final FailState failState;
    private volatile boolean running = true;

    @Nullable
    private volatile Thread thread;

    /**
     * Creates a new {@code ChannelSyncTask}.
     *
     * @param channelId the CLPR channel identifier this task manages
     * @param commitmentLevel the commitment level used when reading on-chain state
     * @param stateReader reads on-chain channel state
     * @param bundleConstructor supplies cached bundle payloads for the channel
     * @param proofCodec decodes (and replay-trims) bundle proofs received from peers
     * @param txSubmitter submits verified bundles to the EVM contract
     * @param peerSelector selects the remote peer to sync with
     * @param clprEndpointClient gRPC client used to send sync payloads to peers
     * @param peerManifestVersions records the manifest version the peer reports on the outbound leg
     * @param metrics the metrics registry
     * @param backoffBaseMs first-failure backoff in ms (doubled per consecutive failure)
     * @param backoffCapMs ceiling on the per-failure backoff in ms
     * @param syncIntervalMs     sync intervals for running channel tasks
     */
    public ChannelSyncTask(
            final Bytes channelId,
            final CommitmentLevel commitmentLevel,
            final ContractStateReader stateReader,
            final BundleConstructor bundleConstructor,
            final BundlePayloadCodec proofCodec,
            final TransactionSubmitter txSubmitter,
            final PeerSelector peerSelector,
            final ClprEndpointClient clprEndpointClient,
            final PeerManifestVersionCache peerManifestVersions,
            final Metrics metrics,
            final long backoffBaseMs,
            final long backoffCapMs,
            final long syncIntervalMs) {
        this.channelId = channelId;
        this.commitmentLevel = commitmentLevel;
        this.stateReader = stateReader;
        this.bundleConstructor = bundleConstructor;
        this.proofCodec = proofCodec;
        this.txSubmitter = txSubmitter;
        this.peerSelector = peerSelector;
        this.clprEndpointClient = clprEndpointClient;
        this.peerManifestVersions = Objects.requireNonNull(peerManifestVersions, "peerManifestVersions");
        this.syncIntervalMs = syncIntervalMs;

        this.channelIdLabel = HexFormat.of().formatHex(channelId.toByteArray());
        this.cyclesTotal = metrics.getOrCreate(
                new Counter.Config("sync", MetricLabels.labeled("cycles.total", "channel_id", channelIdLabel))
                        .withDescription("Total sync cycles attempted"));
        this.cyclesFailed = new LabeledCounter("sync", "cycles.failed", "Sync cycles that threw an exception", metrics);
        this.noPeerSkips = metrics.getOrCreate(
                new Counter.Config("sync", MetricLabels.labeled("no_peer_skips", "channel_id", channelIdLabel))
                        .withDescription("Cycles that skipped syncing because no peer was available"));
        this.noProofSkips = metrics.getOrCreate(
                new Counter.Config("sync", MetricLabels.labeled("no_proof_skips", "channel_id", channelIdLabel))
                        .withDescription("Cycles that skipped syncing because no cached proof was ready"));

        this.peerSyncSuccess =
                new LabeledCounter("sync", "outbound.peer.success", "Successful outbound sync RPCs by peer", metrics);
        this.peerSyncErrors =
                new LabeledCounter("sync", "outbound.peer.errors", "Failed outbound sync RPCs by peer", metrics);
        this.outboundAttempts =
                new LabeledCounter("sync", "outbound.attempts", "Outbound peer sync RPCs attempted", metrics);

        this.failState = new FailState(backoffBaseMs, backoffCapMs);
    }

    /**
     * Run the sync loop on a virtual thread until {@link #stop()} or the channel transitions to
     * {@link ClprChannelStatus#CLOSED}.
     *
     * <p>Any unchecked exception from a cycle is caught and routed through {@link FailState}: the
     * loop logs at ERROR, backs off (bounded, capped — never the tight sync interval), and continues,
     * so a channel never dies silently; the first clean cycle resets the backoff. An interrupt —
     * from a cadence sleep or mid-backoff — breaks the loop.
     */
    public void run() {
        this.thread = Thread.currentThread();
        while (running) {
            try {
                executeSyncCycle();
                final int cleared = failState.onSuccess();
                if (cleared > 0) {
                    log.info("recovered after {} consecutive failure(s)", cleared);
                }
                if (running) {
                    // Wait for the next sync interval derived from settings.
                    Thread.sleep(syncIntervalMs);
                } else {
                    return;
                }
            } catch (final InterruptedException e) {
                // Shut down
                Thread.currentThread().interrupt();
                break;
            } catch (final Exception e) {
                if (!running) {
                    // Shutting down: a cycle that fails because resources were torn down mid-flight
                    // is expected teardown, not a fault. Exit quietly rather than logging at ERROR.
                    break;
                }
                cyclesFailed.increment("channel_id", channelIdLabel, "reason", classifySyncError(e));
                final var d = failState.onFailure(e);
                if (d.logFullDetail()) {
                    log.error("failure #{} fp={}: {}", e, d.consecutiveFailures(), d.fingerprint(), e.getMessage());
                } else {
                    log.error("failure #{} fp={} (repeat)", d.consecutiveFailures(), d.fingerprint());
                }
                try {
                    //noinspection BusyWait — periodic poll cadence;
                    Thread.sleep(d.backoffMs());
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Execute a single sync cycle.
     *
     * <p>Package-private for testing.
     */
    void executeSyncCycle() {
        cyclesTotal.increment();

        // 1. Read channel state. Empty means the chain has no Channel record for this id
        //    (PENDING commit-only phase, or never registered). Per spec §3.1.3 that is
        //    "no messaging, no syncing" — idle this cycle and re-check next tick.
        final var channelOpt = stateReader.readChannelState(channelId, commitmentLevel.toBlockTag());
        if (channelOpt.isEmpty()) {
            log.debug("no Channel record yet — idling cycle");
            return;
        }
        final var channel = channelOpt.get();
        final var status = channel.status();
        // Per-cycle steady-state diagnostic — kept at DEBUG so prod logs aren't flooded.
        log.debug(
                "SyncCycle status={} next={} acked={} recv={}",
                status,
                channel.nextMessageId(),
                channel.ackedMessageId(),
                channel.receivedMessageId());

        // 2. CLOSED → terminate the task; any other live status (ACTIVE / PAUSED / CLOSING /
        //    DRAINED) keeps syncing, because the spec's §4.2 / §4.5 auto-transitions out of those
        //    states are driven by continued bundle traffic — short-circuiting outbound sync on
        //    PAUSED/CLOSING/DRAINED produces symmetric stalemates (D-state-2/3/4). PENDING is not
        //    expected on a stored record (the chain never persists PENDING Channels), but if it
        //    ever appears we idle rather than race forward.
        switch (status) {
            case CLOSED -> {
                log.info("CLOSED, stopping");
                running = false;
                return;
            }
            case PENDING -> {
                log.warn("unexpected PENDING status from chain — idling");
                return;
            }
            default -> {
                /* ACTIVE / PAUSED / CLOSING / DRAINED → fall through and keep syncing. */
            }
        }

        // 3. Get cached bundle if available.
        final var ourBundlePayload =
                bundleConstructor.getLatestBundlePayload(channelId).orElse(Bytes.EMPTY);

        if (ourBundlePayload.equals(Bytes.EMPTY)) {
            noProofSkips.increment();
            log.debug("no cached bundle payload");
            return;
        }

        // 4. Select peer
        var peerOpt = peerSelector.selectPeer();
        if (peerOpt.isEmpty()) {
            noPeerSkips.increment();
            log.debug("no peer available");
            return;
        }
        final var peer = peerOpt.get();
        final var serviceEndpoint = peer.serviceEndpoint();
        if (serviceEndpoint == null) {
            log.warn("Selected peer has no service endpoint: {}", peer);
            peerSelector.recordFailure(peer);
            return;
        }
        final String peerAddress = serviceEndpoint.ipAddress() + ":" + serviceEndpoint.port();
        log.debug("selected peer {}", peerAddress);

        try {
            // 6. outbound sync
            log.debug("bundle SEND→ {} {}", peerAddress, BundleLog.tag(ourBundlePayload));
            final var ourSyncPayload = ClprSyncPayload.newBuilder()
                    .channelId(channelId)
                    .bundlePayload(ourBundlePayload)
                    .build();
            outboundAttempts.increment("channel_id", channelIdLabel);
            // Dial mutual TLS when the peer publishes a certificate on-chain; plaintext otherwise.
            final var response = clprEndpointClient.sync(
                    serviceEndpoint.ipAddress(), serviceEndpoint.port(), ourSyncPayload, peer.tlsCertificate());
            // The RPC completed, so the peer is reachable: credit its health now, regardless of whether
            // its response carried a bundle. Bundle outcome (below) drives the bundle metrics, not peer
            // selection.
            peerSelector.recordSuccess(peer);
            peerSyncSuccess.increment("channel_id", channelIdLabel, "peer", peerAddress);
            log.debug("bundle RECV← {} {}", peerAddress, BundleLog.tag(response.bundlePayload()));

            if (response.bundlePayload().length() > 0) {
                // Trim messages the local ledger has already accepted before submitting: the codec drops
                // already-delivered messages and re-encodes the retained suffix into the peer's proof
                // format (verbatim when nothing was trimmed or the format is read-only, e.g. Hiero), so an
                // already-delivered message can't sink the bundle (and its acknowledgment) as a replay.
                final var parsed = proofCodec.parseBundle(response.bundlePayload(), channel.receivedMessageId());
                log.debug(
                        "bundle PARSED {} {}",
                        BundleLog.coords(parsed.metadata(), parsed.messages().size()),
                        BundleLog.tag(parsed.rawProofBytes()));
                // Record the manifest version the peer reports holding of our manifest. On the
                // outbound leg the peer's response is the only place this version surfaces; without
                // recording it, an outbound-only topology (the peer never inbound-syncs to us) would
                // re-attach our manifest proof to every bundle forever. The cache is monotonic (max),
                // so the inbound handler and this path can both feed it safely.
                peerManifestVersions.record(channelId, parsed.metadata().endpointManifestVersion());
                // submitBundle enqueues onto the signing account's serial submitter and returns
                // immediately. The submission outcome — preview-skipped (stale/replay/…) versus
                // submitted/reverted, plus message and byte volume — is metered inside the
                // AccountTransactionSubmitter, the only layer that knows the on-chain result.
                txSubmitter.submitBundle(channel, parsed);
            }
        } catch (final Exception e) {
            if (!running) {
                // Shutting down: the peer client may already be closed or the RPC cancelled
                // mid-flight. Expected teardown — don't log it or record it as a peer failure.
                return;
            }
            cyclesFailed.increment("channel_id", channelIdLabel, "reason", classifySyncError(e));
            peerSelector.recordFailure(peer);
            peerSyncErrors.increment("channel_id", channelIdLabel, "peer", peerAddress);
            log.warn("Sync failed for channel {}", e, channelIdLabel);
        }
    }

    /**
     * Classifies a sync-cycle exception into a short reason label for the
     * {@code sync.cycles.failed} metric.
     *
     * <p>{@code clpr-relay-sync} does not depend on gRPC directly, so we identify
     * {@code StatusRuntimeException} and {@code StatusException} by class name rather
     * than by an {@code instanceof} check.
     */
    private static String classifySyncError(final Exception e) {
        final String cn = e.getClass().getName();
        if (cn.contains("StatusRuntimeException") || cn.contains("StatusException")) {
            return "grpc_error";
        }
        if (e instanceof IllegalArgumentException) {
            return "parse_error";
        }
        return "runtime_error";
    }

    /**
     * Stop the sync loop after the current cycle completes.
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
     * Returns {@code true} if the sync loop is still running.
     *
     * @return whether the task is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the channel identifier managed by this task.
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
}
