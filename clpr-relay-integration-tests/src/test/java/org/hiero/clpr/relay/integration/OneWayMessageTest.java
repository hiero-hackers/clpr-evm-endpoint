// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.hiero.clpr.relay.evm.JsonRpcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Verify that a message submitted on relay A's chain is delivered to relay B's chain
 * via the sync protocol.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class OneWayMessageTest extends IntegrationTestBase {

    @Test
    void messageFromAArrivesOnB() {
        final byte[] targetApp = hexBytes(contractsB.mockAppAddress());
        final byte[] msgData = "HELLO".getBytes(StandardCharsets.UTF_8);

        interactorA.sendMessage(channelIdAtoB, connectorIdAtoB, targetApp, msgData);

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
    }
}
