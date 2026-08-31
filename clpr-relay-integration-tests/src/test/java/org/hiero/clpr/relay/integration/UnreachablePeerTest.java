// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Verifies the relay's per-channel sync loop survives when the configured peer's gRPC port is
 * unreachable (connection refused): the loop must not crash, must keep attempting outbound sync
 * (incrementing {@code sync.outbound.attempts}), and must leave the relay alive.
 *
 * <p>Builds on {@link OneSidedStubPeerTestBase}, whose channel roster points at a live
 * {@link org.hiero.clpr.relay.test.harness.StubPeer}. Closing that peer turns its bound port into a
 * dead port; {@link #restartFullDuplex()} then starts the relay's outbound sync loop, which dials
 * that dead port. A single unacked outbound message gives the relay real progress to push — without
 * it the relay constructs no bundle and never dials the peer (post-C7 push-iff-progress), so
 * {@code outbound.attempts} would stay 0. Each failed dial routes through {@code FailState}
 * (exponential backoff, never terminates); the arithmetic is unit-tested in
 * {@code ChannelSyncTaskTest}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class UnreachablePeerTest extends OneSidedStubPeerTestBase {

    private static final Duration FIRST_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void relayWithUnreachablePeer_survivesAndKeepsAttemptingSync() throws Exception {
        // Make the peer unreachable, then bring up the relay's outbound sync loop against the now-dead port.
        peer.close();
        restartFullDuplex();

        // Give the relay something to push. A pending unacked message makes BundleConstructor produce a
        // non-empty bundle every cycle, so the relay dials the (dead) peer instead of skipping silently.
        interactor.sendMessage(channelId, connectorId, targetApp(), "ping".getBytes());

        TestConditions.awaitCondition(
                FIRST_ATTEMPT_TIMEOUT, Duration.ofMillis(500), () -> RelayTestSupport.outboundAttempts(relay) > 0);

        assertThat(relay.isRunning())
                .as("Relay must still be running after repeated sync failures to unreachable peer")
                .isTrue();
    }
}
