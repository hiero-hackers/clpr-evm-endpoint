// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.hiero.clpr.relay.evm.JsonRpcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Verify that after relay B processes a message from A, the "PONG" reply produced by
 * B's mock application is delivered back to A as a new inbound message.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class ReplyFlowTest extends IntegrationTestBase {

    @Test
    void replyFlowsBackToOriginator() {
        final byte[] targetApp = hexBytes(contractsB.mockAppAddress());
        final byte[] msgData = "PING".getBytes(StandardCharsets.UTF_8);

        interactorA.sendMessage(channelIdAtoB, connectorIdAtoB, targetApp, msgData);

        // 1. B eventually receives the original message.
        TestConditions.awaitCondition(Duration.ofSeconds(60), () -> {
            try {
                return interactorB
                        .readChannelState(channelIdAtoB)
                        .map(s -> s.receivedMessageId() >= 1L)
                        .orElse(false);
            } catch (final JsonRpcException | IndexOutOfBoundsException e) {
                // Transient: channel not yet visible on-chain or bundle not yet landed.
                return false;
            }
        });

        // 2. A eventually receives the PONG reply back from B.
        TestConditions.awaitCondition(Duration.ofSeconds(90), () -> {
            try {
                return interactorA
                        .readChannelState(channelIdAtoB)
                        .map(s -> s.receivedMessageId() >= 1L)
                        .orElse(false);
            } catch (final JsonRpcException | IndexOutOfBoundsException e) {
                // Transient: channel not yet visible on-chain or bundle not yet landed.
                return false;
            }
        });
    }
}
