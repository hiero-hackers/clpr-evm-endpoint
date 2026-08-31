// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration.channelLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.util.EnumSet;
import org.hiero.clpr.relay.integration.OneSidedStubPeerTestBase;
import org.hiero.clpr.relay.integration.TestConditions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A CLOSING channel must NOT be rejected at gate 0 of {@code ClprSyncHandler} — only CLOSED/PENDING are. The
 * relay must keep serving CLOSING (returning a signed reciprocal proof) so lifecycle status can propagate.
 *
 * <p>Drives the channel to CLOSING with a single relay + admin close (no second relay), then injects an
 * authenticated empty-bundle ping via {@link #poke} and asserts the relay answers with a signed proof
 * (65-byte endpoint signature) rather than an empty-proof rejection.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class ClosingChannelSyncStubPeerTest extends OneSidedStubPeerTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void inboundSyncOnClosingChannelIsNotRejected() throws Exception {
        peer.respondEmpty();

        // Send one message so the outbound queue is not fully acked at close time; admin close then
        // transitions ACTIVE → CLOSING (rather than straight to DRAINED — spec §2.1.1).
        interactor.sendMessage(channelId, connectorId, targetApp(), "A->B 0".getBytes());
        interactor.closeChannel(channelId);
        // CLOSING (or DRAINED if it raced past); CLOSED is excluded — the handler correctly rejects CLOSED.
        TestConditions.awaitStatusIn(
                interactor, channelId, EnumSet.of(ClprChannelStatus.CLOSING, ClprChannelStatus.DRAINED), TIMEOUT);

        // Authenticated empty-bundle sync: the relay skips submission but must still return a signed proof.
        final ClprSyncPayload reply = poke(Bytes.EMPTY);

        assertThat(reply.bundlePayload().length())
                .as("CLOSING channel must not be rejected at gate 0 — relay must return a signed proof")
                .isGreaterThan(0L);
    }
}
