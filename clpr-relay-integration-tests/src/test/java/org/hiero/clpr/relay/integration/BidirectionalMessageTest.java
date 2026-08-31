// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.hiero.clpr.relay.evm.JsonRpcException;
import org.hiero.clpr.relay.integration.ContractInteractor.ChannelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Verify that messages can be exchanged in both directions over the same channel.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class BidirectionalMessageTest extends IntegrationTestBase {

    @Test
    void messagesFlowBothWays() {
        final byte[] targetOnB = hexBytes(contractsB.mockAppAddress());
        final byte[] targetOnA = hexBytes(contractsA.mockAppAddress());
        final byte[] msgAtoB = "A-TO-B".getBytes(StandardCharsets.UTF_8);
        final byte[] msgBtoA = "B-TO-A".getBytes(StandardCharsets.UTF_8);

        interactorA.sendMessage(channelIdAtoB, connectorIdAtoB, targetOnB, msgAtoB);
        interactorB.sendMessage(channelIdAtoB, connectorIdAtoB, targetOnA, msgBtoA);

        TestConditions.awaitCondition(Duration.ofSeconds(90), () -> {
            try {
                final boolean bReady = interactorB
                        .readChannelState(channelIdAtoB)
                        .map(s -> s.receivedMessageId() >= 1L)
                        .orElse(false);
                final boolean aReady = interactorA
                        .readChannelState(channelIdAtoB)
                        .map(s -> s.receivedMessageId() >= 1L)
                        .orElse(false);
                return bReady && aReady;
            } catch (final JsonRpcException | IndexOutOfBoundsException e) {
                // Transient: channel not yet visible on-chain or bundle not yet landed.
                return false;
            }
        });
    }

    @Test
    void simultaneousBidirectionalSend_acksComplete() {
        final byte[] targetOnB = hexBytes(contractsB.mockAppAddress());
        final byte[] targetOnA = hexBytes(contractsA.mockAppAddress());

        // Both send at once → both reach recv=1, acked=0 → the symmetric replay deadlock.
        interactorA.sendMessage(channelIdAtoB, connectorIdAtoB, targetOnB, "A->B".getBytes());
        interactorB.sendMessage(channelIdAtoB, connectorIdAtoB, targetOnA, "B->A".getBytes());

        // Each side must eventually learn the peer received its message (ackedMessageId >= 1).
        // With the bug this never happens — acked stays 0 forever, so this times out.
        TestConditions.awaitCondition(Duration.ofSeconds(60), () -> {
            final long aAcked = interactorA
                    .readChannelState(channelIdAtoB)
                    .map(ChannelState::ackedMessageId)
                    .orElse(0L);
            final long bAcked = interactorB
                    .readChannelState(channelIdAtoB)
                    .map(ChannelState::ackedMessageId)
                    .orElse(0L);
            return aAcked >= 1 && bAcked >= 1;
        });
    }
}
