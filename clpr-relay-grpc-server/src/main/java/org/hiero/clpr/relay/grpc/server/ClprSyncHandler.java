// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.context.Context;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Metrics;
import java.util.HexFormat;
import java.util.function.Function;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.BundleLog;
import org.hiero.clpr.relay.core.BundlePayloadCodec;
import org.hiero.clpr.relay.core.BundlePayloadCodecResolver;
import org.hiero.clpr.relay.core.ChannelLookup;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ParsedBundle;
import org.hiero.clpr.relay.core.PeerManifestVersionCache;
import org.hiero.clpr.relay.core.TransactionSubmitter;
import org.hiero.clpr.relay.core.metrics.LabeledCounter;
import org.jspecify.annotations.Nullable;

/**
 * Handles inbound {@code sync} RPCs from peer endpoints.
 *
 * <p>On each inbound payload the handler:
 * <ol>
 *   <li>Checks that the local channel is not CLOSED/PENDING; returns an empty-proof response if so.</li>
 *   <li>When the bundle is non-empty: decodes the peer's proof via {@link BundlePayloadCodec}, then
 *       applies the per-channel {@link ThrottleEnforcer} (bundle size and message count limits
 *       per spec §3.1.1). Submission is skipped when the throttle rejects the bundle.</li>
 *   <li>When the throttle accepts: submits the bundle to the local chain via
 *       {@link TransactionSubmitter}.</li>
 *   <li>Constructs a response containing the local endpoint's latest proof.</li>
 * </ol>
 *
 * <p>Submission failures are logged but do not prevent the response from being sent, since the
 * peer still needs our latest proof.
 */
public class ClprSyncHandler {

    private static final Logger log = Loggers.getLogger(ClprSyncHandler.class);

    private final BundlePayloadCodecResolver codecResolver;
    private final BundleConstructor proofConstructor;
    private final TransactionSubmitter txSubmitter;
    private final Function<Bytes, ThrottleEnforcer> throttleResolver;
    private final ChannelLookup channelLookup;
    private final PeerManifestVersionCache peerManifestVersions;

    private final LabeledCounter syncRequests;
    private final LabeledCounter syncErrors;
    private final String instanceName;

    /**
     * Primary constructor; wires in the metrics registry.
     *
     * @param codecResolver     resolves the per-channel codec that decodes (and replay-trims) inbound proofs
     * @param proofConstructor     supplies the local endpoint's latest cached proof
     * @param txSubmitter          submits verified bundles to the EVM contract (applies
     *                             gas-free pre-verification internally when configured)
     * @param channelLookup     resolves local channel state by id (the only reader role this
     *                             handler needs)
     * @param peerManifestVersions records, per channel, the manifest version the peer reports
     *                             holding of our manifest (from the inbound metadata), so the bundle
     *                             constructor can decide whether to re-send our manifest proof
     * @param metrics              metrics registry
     * @param instanceName free-form label stamped onto the relay's worker-loop log context under
     *                     the {@code relay} key; a blank value adds no context entry
     */
    public ClprSyncHandler(
            final BundlePayloadCodecResolver codecResolver,
            final BundleConstructor proofConstructor,
            final TransactionSubmitter txSubmitter,
            final Function<Bytes, ThrottleEnforcer> throttleResolver,
            final ChannelLookup channelLookup,
            final PeerManifestVersionCache peerManifestVersions,
            final Metrics metrics,
            final String instanceName) {
        this.codecResolver = codecResolver;
        this.proofConstructor = proofConstructor;
        this.txSubmitter = txSubmitter;
        this.throttleResolver = throttleResolver;
        this.channelLookup = channelLookup;
        this.peerManifestVersions = peerManifestVersions;
        this.instanceName = instanceName;

        // sync.requests carries only {channel_id}.
        this.syncRequests = new LabeledCounter("grpc", "sync.requests", "Inbound sync RPCs received", metrics);
        this.syncErrors = new LabeledCounter(
                "grpc", "sync.errors", "Inbound sync RPCs whose parse/submit step threw, labelled by reason", metrics);
    }

    /**
     * Handle an inbound sync request and produce a response.
     *
     * @param inboundPayload the sync payload received from the peer
     * @param peer           the authenticated peer endpoint resolved from the mutual-TLS client
     *                       certificate, or {@code null} when the dialer presented no roster-matching
     *                       certificate (a plaintext connection, or an unrecognized certificate)
     * @return the response payload containing our latest proof
     */
    public ClprSyncPayload handleSync(final ClprSyncPayload inboundPayload, @Nullable final ClprEndpoint peer) {
        final Bytes channelId = inboundPayload.channelId();
        final String channelIdLabel = HexFormat.of().formatHex(channelId.toByteArray());
        try (var _ = Context.getThreadLocalContext().add("relay", instanceName);
                var _ = Context.getThreadLocalContext().add("conn", channelIdLabel);
                var _ = Context.getThreadLocalContext()
                        .add("peer", peer != null ? peer.accountId().toHex() : "anonymous"); ) {
            syncRequests.increment("channel_id", channelIdLabel);
            // TODO(per-channel-auth): mTLS only proves the dialer holds *some* roster CA (the union
            //  across every channel), not that it is a party to THIS channelId. In a multi-channel
            //  relay with disjoint rosters, a peer from channel A can pull B's proof or submit to B.
            //  Close the loop by resolving `peer` against the per-channel roster
            //  (peerCaches.get(channelId)) and rejecting when it is not a member, rather than trusting
            //  the union.
            final Bytes bundlePayload = inboundPayload.bundlePayload();
            log.debug("bundle IN← {}", BundleLog.tag(bundlePayload));

            // 0. Check local channel state. Rejects with empty-proof when there is no Channel
            //    record (PENDING commit-only or never registered)
            final var localChannelOpt = channelLookup.readChannelState(channelId, CommitmentLevel.LATEST.toBlockTag());
            if (localChannelOpt.isEmpty()) {
                log.info("No channel record. Returning empty-proof response");
                return emptyProofResponse(channelId);
            }
            final ClprChannel localChannel = localChannelOpt.get();
            if (localChannel.status() == ClprChannelStatus.PENDING) {
                log.info("channel status is {}. Returning empty-proof response", localChannel.status());
                return emptyProofResponse(channelId);
            }
            // When the status is CLOSED means "all processing stops". But we keep accepting
            final boolean acceptInbound = localChannel.status() != ClprChannelStatus.CLOSED;
            //    PAUSED/CLOSING/DRAINED are processed normally, since the spec's
            //    auto-transitions ride on continued bundle traffic.

            // 1b. Submit the peer's bundle when one is present. Empty-bundle pings skip submission
            //     but still receive our latest proof below. submitBundle enqueues onto the signing
            //     account's serial submitter and returns immediately; that submitter runs the gas-free
            //     eth_call preview at send time, so a bundle the chain would revert is skipped without
            //     spending gas — the outcome is handled internally and never surfaced here.
            if (acceptInbound && !bundlePayload.equals(Bytes.EMPTY)) {
                // Split parse and submit into separate try/catch blocks so the error reason label
                // accurately reflects which step failed.
                ParsedBundle parsed = null;
                try {
                    parsed = codecResolver
                            .codecFor(channelId)
                            .parseBundle(bundlePayload, localChannel.receivedMessageId());
                    log.debug(
                            "bundle PARSED {} {}",
                            BundleLog.coords(
                                    parsed.metadata(), parsed.messages().size()),
                            BundleLog.tag(bundlePayload));
                    // Record the manifest version the peer reports holding of our manifest, so the
                    // outbound bundle constructor only re-sends our manifest proof when the peer is behind.
                    peerManifestVersions.record(channelId, parsed.metadata().endpointManifestVersion());
                } catch (final Exception e) {
                    syncErrors.increment("channel_id", channelIdLabel, "reason", "parse_error");
                    log.warn("Failed to parse peer bundle {}", e, BundleLog.tag(bundlePayload));
                }
                // Throttle: byte size against the received payload (max_sync_bytes), and message count /
                // per-message size against the submitted (already replay-trimmed) suffix — the same bundle
                // the on-chain verifier re-checks against these limits when we submit it.
                final ThrottleEnforcer throttle = throttleResolver.apply(channelId);
                if (parsed != null && throttle != null && throttle.shouldAccept(bundlePayload, parsed.messages())) {
                    try {
                        txSubmitter.submitBundle(localChannelOpt.get(), parsed);
                    } catch (final Exception e) {
                        syncErrors.increment("channel_id", channelIdLabel, "reason", "submit_error");
                        log.warn("Failed to submit peer bundle {}", e, BundleLog.tag(bundlePayload));
                    }
                }
            }

            // 2. Build our response with the latest cached proof.
            final Bytes ourPayload =
                    proofConstructor.getLatestBundlePayload(channelId).orElse(Bytes.EMPTY);

            return ClprSyncPayload.newBuilder()
                    .channelId(channelId)
                    .bundlePayload(ourPayload)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Build an empty-proof response for the given channel. */
    private ClprSyncPayload emptyProofResponse(final Bytes channelId) {
        return ClprSyncPayload.newBuilder()
                .channelId(channelId)
                .bundlePayload(Bytes.EMPTY)
                .build();
    }
}
