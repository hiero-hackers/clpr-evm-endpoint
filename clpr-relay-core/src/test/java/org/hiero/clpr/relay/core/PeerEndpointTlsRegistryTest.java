// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class PeerEndpointTlsRegistryTest {

    private static final Bytes CONN_A = Bytes.wrap(new byte[] {1});
    private static final Bytes CONN_B = Bytes.wrap(new byte[] {2});

    @Test
    void emptyRegistry_returnsEmpty() {
        final var registry = new PeerEndpointTlsRegistry();

        assertThat(registry.matchByCa(CertFixtures.leafUnderA().cert())).isEmpty();
    }

    @Test
    void nullLeaf_returnsEmpty() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN_A, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));

        assertThat(registry.matchByCa(null)).isEmpty();
    }

    @Test
    void matchesCaFromRegisteredChannel() {
        final var registry = new PeerEndpointTlsRegistry();
        final var endpoint = CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER);
        registry.update(CONN_A, List.of(endpoint));

        assertThat(registry.matchByCa(CertFixtures.leafUnderA().cert())).contains(endpoint);
    }

    @Test
    void doesNotMatchLeafFromUnregisteredCa() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN_A, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));

        assertThat(registry.matchByCa(CertFixtures.leafUnderB().cert())).isEmpty();
    }

    @Test
    void matchesAcrossMultipleChannels() {
        final var registry = new PeerEndpointTlsRegistry();
        final var endpointA = CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER);
        final var endpointB = CertFixtures.endpointWithCert(CertFixtures.CA_B_CERT_DER);
        registry.update(CONN_A, List.of(endpointA));
        registry.update(CONN_B, List.of(endpointB));

        assertThat(registry.matchByCa(CertFixtures.leafUnderA().cert())).contains(endpointA);
        assertThat(registry.matchByCa(CertFixtures.leafUnderB().cert())).contains(endpointB);
    }

    @Test
    void update_invalidatesCachedPositiveMatch() {
        final var registry = new PeerEndpointTlsRegistry();
        final var endpoint = CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER);
        registry.update(CONN_A, List.of(endpoint));

        final var leaf = CertFixtures.leafUnderA().cert();
        assertThat(registry.matchByCa(leaf)).isPresent(); // populates cache

        registry.update(CONN_A, List.of()); // roster shrinks — CA_A no longer trusted
        assertThat(registry.matchByCa(leaf)).isEmpty(); // stale cache must not be served
    }

    @Test
    void matchByCa_reflectsRosterAddition() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN_A, List.of());

        final var leaf = CertFixtures.leafUnderA().cert();
        assertThat(registry.matchByCa(leaf)).isEmpty(); // populates cache with empty

        registry.update(CONN_A, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        assertThat(registry.matchByCa(leaf)).isPresent(); // must reflect new roster
    }

    @Test
    void emptyRosterClearsThatChannelsEntry() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN_A, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        registry.update(CONN_B, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_B_CERT_DER)));

        registry.update(CONN_A, List.of());

        assertThat(registry.matchByCa(CertFixtures.leafUnderA().cert())).isEmpty();
        assertThat(registry.matchByCa(CertFixtures.leafUnderB().cert())).isPresent();
    }
}
