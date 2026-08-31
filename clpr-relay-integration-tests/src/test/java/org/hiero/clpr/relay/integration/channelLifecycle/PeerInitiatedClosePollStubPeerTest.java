// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration.channelLifecycle;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import org.hiero.clpr.relay.integration.ContractInteractor;
import org.hiero.clpr.relay.integration.OneSidedStubPeerTestBase;
import org.hiero.clpr.relay.integration.TestConditions;
import org.hiero.clpr.relay.test.harness.StubBundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Peer-initiated close via the <b>poll</b> path: the same single bundle as the inbound sibling (ack +
 * reply + {@code status=CLOSING}), but delivered as the peer's <em>response</em> to the relay's own
 * outbound sync, exercising {@code ChannelSyncTask}'s response-submission path. With no admin close,
 * the relay drives {@code ACTIVE → CLOSING → DRAINED} entirely off the polled response, then on to
 * {@code CLOSED} once the peer's response declares it is also {@code DRAINED}.
 *
 * <p>The {@code onSync} responder acks ONLY once the relay actually has outbound (its proof is non-empty).
 * Acking before that would revert on the contract ({@code received ≥ next}); the dedup submitter caches
 * even a reverted bundle's fingerprint, so the identical real bundle would then be dropped as
 * {@code SKIPPED}, wedging the channel.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class PeerInitiatedClosePollStubPeerTest extends OneSidedStubPeerTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Test
    void peerDeclaringClosingViaPollDrivesLocalCloseAndDrain() {
        final Bytes closing = StubBundles.hieroStateProof()
                .ackedMessageId(0)
                .receivedMessageId(1)
                .reply(1L, ClprMessageReplyStatus.SUCCESS, new byte[] {1})
                .status(ClprChannelStatus.CLOSING)
                .build();
        peer.onSync(req -> ClprSyncPayload.newBuilder()
                .channelId(Bytes.wrap(channelId))
                .bundlePayload(req.bundlePayload().length() == 0 ? Bytes.EMPTY : closing)
                .build());

        // Bring up the relay's own sync loop, then enqueue the outbound DATA. The relay polls the peer,
        // gets back {ack + reply + CLOSING}, and drives itself ACTIVE → CLOSING → DRAINED — no admin close.
        restartFullDuplex();
        interactor.sendMessage(channelId, connectorId, targetApp(), "A->B 0".getBytes());

        TestConditions.awaitStatus(interactor, channelId, ClprChannelStatus.DRAINED, TIMEOUT);

        // The peer, now also drained, answers every subsequent poll with a status-only DRAINED bundle.
        // With all outbound acked and the peer reporting DRAINED, the relay completes the handshake:
        // DRAINED → CLOSED.
        final ContractInteractor.ChannelState drained = interactor.requireChannelState(channelId);
        final Bytes drainedBundle = StubBundles.hieroStateProof()
                .ackedMessageId(drained.receivedMessageId())
                .receivedMessageId(drained.ackedMessageId())
                .chainFrom(Bytes.wrap(drained.receivedRunningHash()))
                .status(ClprChannelStatus.DRAINED)
                .build();
        peer.respondWith(ClprSyncPayload.newBuilder()
                .bundlePayload(drainedBundle)
                .channelId(Bytes.wrap(channelId))
                .build());

        TestConditions.awaitStatus(interactor, channelId, ClprChannelStatus.CLOSED, TIMEOUT);
    }
}
