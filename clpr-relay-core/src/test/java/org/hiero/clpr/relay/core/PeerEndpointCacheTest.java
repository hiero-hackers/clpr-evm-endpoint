// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeerEndpointCacheTest {
    private PeerEndpointCache cache;
    private ClprEndpoint peer1;
    private ClprEndpoint peer2;

    @BeforeEach
    void setUp() {
        cache = new PeerEndpointCache();
        peer1 = ClprEndpoint.newBuilder().accountId(Bytes.wrap(new byte[] {1})).build();
        peer2 = ClprEndpoint.newBuilder().accountId(Bytes.wrap(new byte[] {2})).build();
    }

    @Test
    void emptyByDefault() {
        assertThat(cache.hasPeers()).isFalse();
        assertThat(cache.size()).isZero();
        assertThat(cache.allPeers()).isEmpty();
    }

    @Test
    void addAndRetrievePeer() {
        cache.replaceAll(List.of(peer1));

        assertThat(cache.hasPeers()).isTrue();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.allPeers()).containsExactly(peer1);
    }

    @Test
    void addDuplicateUpdatesExisting() {
        cache.replaceAll(List.of(peer1));

        // Create a new endpoint with the same accountId but different tls cert
        ClprEndpoint peer1Updated = ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[] {1}))
                .tlsCertificate(Bytes.wrap(new byte[] {9, 9, 9}))
                .build();
        cache.replaceAll(List.of(peer1Updated));

        // Should still have only one peer
        assertThat(cache.size()).isEqualTo(1);
        // Should be the updated version
        assertThat(cache.allPeers()).containsExactly(peer1Updated);
    }

    @Test
    void sizeReflectsContent() {
        assertThat(cache.size()).isZero();

        cache.replaceAll(List.of(peer1));
        assertThat(cache.size()).isEqualTo(1);

        cache.replaceAll(List.of(peer1, peer2));
        assertThat(cache.size()).isEqualTo(2);

        cache.replaceAll(List.of(peer1));
        assertThat(cache.size()).isEqualTo(1);

        cache.replaceAll(List.of());
        assertThat(cache.size()).isZero();
    }

    private static ClprEndpoint endpoint(int accountId) {
        return ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[] {(byte) accountId}))
                .build();
    }

    @Test
    void replaceAll_installsExactlyTheGivenRoster() {
        final var cache = new PeerEndpointCache();
        cache.replaceAll(List.of(endpoint(1), endpoint(2)));

        cache.replaceAll(List.of(endpoint(2), endpoint(3)));

        assertThat(cache.allPeers())
                .extracting(ClprEndpoint::accountId)
                .containsExactlyInAnyOrder(endpoint(2).accountId(), endpoint(3).accountId());
    }

    @Test
    void replaceAll_dropsDepartedPeers() {
        final var cache = new PeerEndpointCache();
        cache.replaceAll(List.of(endpoint(1), endpoint(2)));

        cache.replaceAll(List.of(endpoint(2)));

        assertThat(cache.allPeers())
                .extracting(ClprEndpoint::accountId)
                .containsExactly(endpoint(2).accountId());
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void replaceAll_emptyClearsTheCache() {
        final var cache = new PeerEndpointCache();
        cache.replaceAll(List.of(endpoint(1)));

        cache.replaceAll(List.of());

        assertThat(cache.hasPeers()).isFalse();
        assertThat(cache.allPeers()).isEmpty();
    }

    @Test
    void allPeers_neverObservesPartialStateDuringConcurrentReplace() throws Exception {
        final var cache = new PeerEndpointCache();
        final var rosterA = List.of(endpoint(1), endpoint(2), endpoint(3));
        final var rosterB = List.of(endpoint(4), endpoint(5), endpoint(6));
        cache.replaceAll(rosterA);

        final var error = new AtomicReference<Throwable>();
        final var stop = new AtomicBoolean(false);
        final var reader = Thread.startVirtualThread(() -> {
            try {
                while (!stop.get()) {
                    // Each replaceAll installs a complete three-element roster, so a reader must
                    // always observe exactly three peers — never a partial or empty intermediate.
                    assertThat(cache.allPeers()).hasSize(3);
                }
            } catch (final Throwable t) {
                error.set(t);
            }
        });

        for (int i = 0; i < 1_000; i++) {
            cache.replaceAll(i % 2 == 0 ? rosterB : rosterA);
        }
        stop.set(true);
        reader.join(2_000L);

        assertThat(error.get()).isNull();
    }
}
