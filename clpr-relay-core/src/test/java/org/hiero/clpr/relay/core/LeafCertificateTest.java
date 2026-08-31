// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.X509Certificate;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LeafCertificate} — the in-memory Ed25519 leaf minted under an ECDSA P-384 CA. */
class LeafCertificateTest {

    @Test
    void generatesAnEd25519LeafAndMatchingKey() throws Exception {
        final LeafCertificate leaf = CertFixtures.generate(CertFixtures.CA_A_KEY);

        // The Edwards-curve algorithm surfaces as either "Ed25519" or the umbrella "EdDSA" depending on
        // the provider that parsed the key, so accept both rather than pinning one provider's spelling.
        assertThat(leaf.cert().getPublicKey().getAlgorithm()).isIn("Ed25519", "EdDSA");
        // The key and certificate are a matching pair — the contract that actually matters.
        assertThat(Certs.keyMatchesCertificate(leaf.key(), leaf.cert())).isTrue();
    }

    @Test
    void leafChainsToItsIssuingCa() throws Exception {
        final X509Certificate leaf =
                CertFixtures.generate(CertFixtures.CA_A_KEY).cert();

        assertThat(Certs.chainsTo(leaf, CertFixtures.CA_A_CERT)).isTrue();
    }

    @Test
    void leafIsAnEndEntityWithDigitalSignatureUsage() throws Exception {
        final X509Certificate leaf =
                CertFixtures.generate(CertFixtures.CA_A_KEY).cert();

        // getBasicConstraints() returns -1 for a non-CA certificate.
        assertThat(leaf.getBasicConstraints()).isEqualTo(-1);
        // keyUsage bit 0 is digitalSignature; keyCertSign (bit 5) must be absent.
        final boolean[] keyUsage = leaf.getKeyUsage();
        assertThat(keyUsage).isNotNull();
        assertThat(keyUsage[0]).isTrue();
        assertThat(keyUsage[5]).isFalse();
    }

    @Test
    void eachInvocationGeneratesAFreshKey() throws Exception {
        final LeafCertificate first = CertFixtures.generate(CertFixtures.CA_A_KEY);
        final LeafCertificate second = CertFixtures.generate(CertFixtures.CA_A_KEY);

        assertThat(first.cert().getPublicKey()).isNotEqualTo(second.cert().getPublicKey());
    }
}
