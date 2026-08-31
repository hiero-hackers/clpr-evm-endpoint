// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import org.hiero.clpr.relay.core.Certs;
import org.hiero.clpr.relay.core.LeafCertificate;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class ClientSideTrustManagerTest {

    private static X509Certificate[] chain(final X509Certificate leaf) {
        return new X509Certificate[] {leaf};
    }

    @Test
    void acceptsLeafChainingToTheExpectedCa() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));

        assertThatCode(() ->
                        tm.checkServerTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLeafUnderADifferentCa() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));

        assertThatThrownBy(() ->
                        tm.checkServerTrusted(chain(CertFixtures.leafUnderB().cert()), "ECDSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("does not chain");
    }

    @Test
    void rejectsExpiredLeaf() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));
        // validity = -3601 s → notAfter = now + (−3601 s) + CLOCK_SKEW(1 h) = now − 1 s
        final X509Certificate expired = LeafCertificate.generate(CertFixtures.CA_A_KEY, Duration.ofSeconds(-3601))
                .cert();

        assertThatThrownBy(() -> tm.checkServerTrusted(chain(expired), "ECDSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("does not chain");
    }

    @Test
    void rejectsEmptyChain() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));

        assertThatThrownBy(() -> tm.checkServerTrusted(new X509Certificate[0], "ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void rejectsNullChain() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));

        assertThatThrownBy(() -> tm.checkServerTrusted(null, "ECDSA")).isInstanceOf(CertificateException.class);
    }

    @Test
    void checkClientTrustedIsUnsupported() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));

        assertThatThrownBy(() ->
                        tm.checkClientTrusted(chain(CertFixtures.leafUnderA().cert()), "ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void acceptedIssuersIsEmpty() throws Exception {
        final var tm = new ClientSideTrustManager(Certs.parse(CertFixtures.CA_A_CERT_DER));

        assertThat(tm.getAcceptedIssuers()).isEmpty();
    }
}
