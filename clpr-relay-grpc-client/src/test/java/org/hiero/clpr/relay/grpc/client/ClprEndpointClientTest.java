// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import io.grpc.StatusRuntimeException;
import java.net.ServerSocket;
import java.time.Duration;
import org.hiero.clpr.relay.core.Certs;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class ClprEndpointClientTest {

    private Metrics metrics = new SimpleMetrics();

    private static ClprSyncPayload emptyPayload() {
        return ClprSyncPayload.newBuilder()
                .channelId(Bytes.wrap(new byte[32]))
                .bundlePayload(Bytes.EMPTY)
                .build();
    }

    /**
     * Regression: after {@link ClprEndpointClient#close()} a still-running sync task must not be able
     * to build and cache a fresh channel — otherwise that channel outlives the drain and grpc-java's
     * orphan detector logs a {@code SEVERE} when it is garbage collected. The call must fail fast
     * without leaving anything in the cache (no network connection is attempted).
     */
    @Test
    void syncAfterClose_throwsAndLeavesNoCachedChannel() {
        final ClprEndpointClient client = new ClprEndpointClient(1_048_576, null);
        client.close();

        assertThatThrownBy(() -> client.sync("127.0.0.1", 1, emptyPayload())).isInstanceOf(IllegalStateException.class);

        assertThat(client.cachedChannels())
                .as("no channel may be cached once the client is closed")
                .isEmpty();
    }

    /** Returns a port number that is (momentarily) free, so a connect attempt is refused. */
    private static int closedPort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void close_shutsDownAndClearsCachedChannels() throws Exception {
        final int deadPort = closedPort();
        // connection-refused surfaces as UNAVAILABLE (immediate) regardless of timeout — only
        // DEADLINE_EXCEEDED auto-evicts, so the refused channel stays cached for close() to drain.
        final var client = new ClprEndpointClient(1_048_576, null);
        final var payload = ClprSyncPayload.newBuilder()
                .channelId(Bytes.wrap(new byte[] {1}))
                .build();

        // The call fails, but the lazily-created channel stays cached (we only evict on
        // DEADLINE_EXCEEDED) — this is exactly the channel that would otherwise be leaked.
        assertThatThrownBy(() -> client.sync("127.0.0.1", deadPort, payload))
                .isInstanceOf(StatusRuntimeException.class);

        final var cached = client.cachedChannels();
        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).isShutdown()).isFalse();

        client.close();

        assertThat(client.cachedChannels()).isEmpty();
        assertThat(cached.get(0).isShutdown()).isTrue();
    }

    @Test
    void close_isIdempotentAndSafeOnEmptyClient() {
        final var client = new ClprEndpointClient(1_048_576, null);
        assertThatCode(client::close).doesNotThrowAnyException();
        assertThatCode(client::close).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // Outbound TLS: own material + per-peer channel selection
    // ---------------------------------------------------------------------

    @Test
    void secureWithOwnLeaf_constructs() throws Exception {
        final var keyManager =
                new LeafKeyManager(Certs.parsePrivateKey(CertFixtures.CA_A_KEY_DER), Duration.ZERO, metrics);

        try (var client = new ClprEndpointClient(1 << 20, keyManager)) {
            assertThat(client.cachedChannels()).isEmpty();
        }
    }

    @Test
    void plaintext_withNullLeaf_constructs() {
        try (var client = new ClprEndpointClient(1 << 20, null)) {
            assertThat(client.cachedChannels()).isEmpty();
        }
    }

    @Test
    void certRotation_retiresStaleChannelWithoutGrowingCache() throws Exception {
        final int deadPort = closedPort();
        final var keyManager =
                new LeafKeyManager(Certs.parsePrivateKey(CertFixtures.CA_A_KEY_DER), Duration.ZERO, metrics);
        // Secure profile so dials are TLS and channels key on the peer-cert fingerprint.
        // connection-refused is UNAVAILABLE (immediate), so the channel stays cached after the failed call.
        final var client = new ClprEndpointClient(1_048_576, keyManager);

        // First dial pins cert A → one cached channel.
        assertThatThrownBy(() ->
                        client.sync("127.0.0.1", deadPort, emptyPayload(), Bytes.wrap(CertFixtures.CA_A_CERT_DER)))
                .isInstanceOf(StatusRuntimeException.class);
        final var afterFirst = client.cachedChannels();
        assertThat(afterFirst).hasSize(1);
        final var staleChannel = afterFirst.get(0);

        // Peer rotates its on-chain cert to B → the new channel supersedes the old; the cache does not grow.
        assertThatThrownBy(() ->
                        client.sync("127.0.0.1", deadPort, emptyPayload(), Bytes.wrap(CertFixtures.CA_B_CERT_DER)))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(client.cachedChannels()).hasSize(1);
        assertThat(staleChannel.isShutdown())
                .as("the pre-rotation channel is retired")
                .isTrue();

        client.close();
    }

    @Test
    void differentPeers_keepSeparateChannels() throws Exception {
        final int portA;
        final int portB;
        try (var s1 = new ServerSocket(0);
                var s2 = new ServerSocket(0)) {
            portA = s1.getLocalPort();
            portB = s2.getLocalPort();
        }
        // Secure profile so per-peer dials build distinct TLS channels.
        final var client = new ClprEndpointClient(1_048_576, null);

        assertThatThrownBy(
                        () -> client.sync("127.0.0.1", portA, emptyPayload(), Bytes.wrap(CertFixtures.CA_A_CERT_DER)))
                .isInstanceOf(StatusRuntimeException.class);
        assertThatThrownBy(
                        () -> client.sync("127.0.0.1", portB, emptyPayload(), Bytes.wrap(CertFixtures.CA_A_CERT_DER)))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(client.cachedChannels())
                .as("distinct peers keep distinct channels")
                .hasSize(2);

        client.close();
    }
}
