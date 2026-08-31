// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Verifies that the relay dials the stub peer over mutual TLS.
 *
 * <p>The {@link OneSidedStubPeerTestBase} sets up:
 * <ul>
 *   <li>The stub peer's CA-B certificate published in the on-chain roster ({@code tls_certificate}).</li>
 *   <li>The stub peer started with a mutual-TLS listener backed by CA-B's key that requires
 *       connecting clients to present a leaf signed by CA-A.</li>
 *   <li>The relay configured with CA-A's key (so its {@link org.hiero.clpr.relay.core.LeafKeyManager}
 *       is non-null), making {@link org.hiero.clpr.relay.grpc.client.ClprEndpointClient#sync} build
 *       a mutual-TLS channel when it sees a non-empty on-chain {@code tls_certificate}.</li>
 * </ul>
 *
 * <p>A successful {@code StubPeer#awaitSync} proves the full mutual-TLS handshake completed:
 * the relay's client pinned the peer's presented leaf to CA-B's on-chain cert, and the stub
 * peer's server verified the relay's client leaf against CA-A.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class SecureSyncDialTest extends OneSidedStubPeerTestBase {

    @Test
    void relayDialsStubPeerOverTls() {
        // Restart in full-duplex so the outbound sync loop starts.
        restartFullDuplex();

        // Send an outbound message so the bundle constructor produces a non-empty bundle.
        // The sync loop skips the peer dial entirely when there is nothing to send.
        interactor.sendMessage(channelId, connectorId, targetApp(), new byte[] {1});

        // Wait for the relay to dial the stub peer. A non-null result means the full mTLS
        // handshake completed: the relay built a TLS channel (leafKeyManager != null + non-empty
        // on-chain cert), presented its CA-A leaf as client cert, and the stub peer's server
        // verified it against CA-A before accepting the connection.
        final ClprSyncPayload received = peer.awaitSync(Duration.ofSeconds(30));
        assertThat(received)
                .as("relay must dial stub peer over TLS within 30 s")
                .isNotNull();
    }
}
