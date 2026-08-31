// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import java.nio.file.Files;
import java.time.Duration;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Verifies relay-level leaf-certificate scenarios over the full Anvil-backed mTLS path. For
 * direct TLS-handshake rejection tests that require no relay or Anvil, see {@link ClientTlsDialTest}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class LeafRevocationTest extends OneSidedStubPeerTestBase {

    /**
     * A relay presenting a leaf signed by CA-B (the wrong CA) must be rejected by the stub peer,
     * which requires CA-A. No sync payload should reach the stub peer within the observation window.
     */
    @Test
    void relayWithUntrustedCaIsRejectedByMtlsPeer() throws Exception {
        final var wrongKeyPath = tempDir.resolve("wrong-ca.der");
        Files.write(wrongKeyPath, CertFixtures.CA_B_KEY_DER);

        // Relay now holds a CA-B leaf — the stub peer's trust anchor is CA-A, so it will reject
        // every TLS handshake attempt.
        restartFullDuplex(wrongKeyPath.toString(), 86400L);
        peer.drain();

        // Give the relay a bundle to send so the sync loop actually attempts to dial the peer.
        interactor.sendMessage(channelId, connectorId, targetApp(), new byte[] {1});

        final ClprSyncPayload received = peer.awaitSync(Duration.ofSeconds(3));
        assertThat(received)
                .as("stub peer must reject a leaf signed by the wrong CA")
                .isNull();
    }

    /**
     * When the relay's initial leaf expires before the first dial, {@link LeafKeyManager} must
     * transparently rotate to a fresh leaf on the first TLS handshake. The stub peer should accept
     * the new leaf because it still chains to CA-A.
     */
    @Test
    void relayLeafRotatesBeforeFirstDial() throws InterruptedException {
        // 2-second validity: the initial leaf (minted at relay startup) will expire well before
        // the sleep below ends, so the first dial triggers a rotation.
        restartFullDuplex(relayKeyPath.toString(), 2L);
        peer.drain();

        // Wait past the rotation window so the initial leaf's validity has elapsed.
        Thread.sleep(4_000);

        // Give the relay a bundle to send so the sync loop actually dials the peer. The first
        // handshake triggers getCertificateChain(), which sees the rotation window elapsed and
        // mints a new leaf still signed by CA-A.
        interactor.sendMessage(channelId, connectorId, targetApp(), new byte[] {1});

        final ClprSyncPayload received = peer.awaitSync(Duration.ofSeconds(30));
        assertThat(received)
                .as("rotated leaf must still be accepted by stub peer (still signed by CA-A)")
                .isNotNull();
    }
}
