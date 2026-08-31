// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class ServerSideTrustManagerTest {

    private static final Bytes CONN = Bytes.wrap(new byte[] {1});

    private static X509Certificate[] chain(final X509Certificate leaf) {
        return new X509Certificate[] {leaf};
    }

    @Test
    void acceptsClientChainingToARosterCa() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(
                CONN,
                List.of(
                        CertFixtures.endpointWithCert(CertFixtures.CA_B_CERT_DER),
                        CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        final var tm = new ServerSideTrustManager(registry);

        assertThatCode(() ->
                        tm.checkClientTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsClientUnderANonRosterCa() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_B_CERT_DER)));
        final var tm = new ServerSideTrustManager(registry);

        assertThatThrownBy(() ->
                        tm.checkClientTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("does not chain to any known peer roster CA");
    }

    @Test
    void rejectsWhenRosterEmpty() {
        final var tm = new ServerSideTrustManager(new PeerEndpointTlsRegistry());

        assertThatThrownBy(() ->
                        tm.checkClientTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void skipsEmptyCertEntries() {
        final ClprEndpoint noCert =
                ClprEndpoint.newBuilder().accountId(Bytes.wrap(new byte[] {9})).build();
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN, List.of(noCert, CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        final var tm = new ServerSideTrustManager(registry);

        assertThatCode(() ->
                        tm.checkClientTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsMalformedRosterCert() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(
                CONN,
                List.of(
                        CertFixtures.endpointWithCert(new byte[] {1, 2, 3}),
                        CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        final var tm = new ServerSideTrustManager(registry);

        assertThatCode(() ->
                        tm.checkClientTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void readsRosterLiveOnEachHandshake() {
        final var registry = new PeerEndpointTlsRegistry();
        final var tm = new ServerSideTrustManager(registry);
        final X509Certificate leaf = CertFixtures.leafUnderA().cert();

        assertThatThrownBy(() -> tm.checkClientTrusted(chain(leaf), "ECDSA")).isInstanceOf(CertificateException.class);

        registry.update(CONN, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        assertThatCode(() -> tm.checkClientTrusted(chain(leaf), "ECDSA")).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyChain() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        final var tm = new ServerSideTrustManager(registry);

        assertThatThrownBy(() -> tm.checkClientTrusted(new X509Certificate[0], "ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void checkServerTrustedIsUnsupported() {
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN, List.of(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)));
        final var tm = new ServerSideTrustManager(registry);

        assertThatThrownBy(() ->
                        tm.checkServerTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void acceptedIssuersIsEmpty() {
        assertThat(new ServerSideTrustManager(new PeerEndpointTlsRegistry()).getAcceptedIssuers())
                .isEmpty();
    }
}
