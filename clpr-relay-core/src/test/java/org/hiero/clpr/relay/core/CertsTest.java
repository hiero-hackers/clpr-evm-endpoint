// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Certs} private-key parsing, covering the PKCS#8 and PKCS#1 PEM forms.
 *
 * <p>The two fixtures below are the same RSA-2048 key exported by OpenSSL in both encodings
 * ({@code openssl genrsa -traditional} for PKCS#1, {@code openssl pkcs8 -topk8 -nocrypt} for PKCS#8).
 * Parsing either must yield an identical key — that is the contract the PKCS#1→PKCS#8 wrapper must hold.
 */
class CertsTest {

    private static final String PKCS1_PEM = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAwOcUZnU+DPpN5gkbaUx1jzPx0t/qZs++bYPxRbBULw2C34PN
            cQjsKzkD0JwdTZnTJLoMEIccsBQ4N7xXIaudiwhbp7FZbDRDYQSyjtYemW4CpKcJ
            QYwRLDaLCvbwQy/8AWt0eVI/Q9IJqiDGwFnidadAdcexQx2UU7D9k5n5zCzHecXv
            +uuiYJRox2JHjgvT1+vvwOB4rFJpE5LNDJLhaXbXsilOQoj+2x52UYpReXZkJWa2
            WJCuGWjXigee6KGY2iEH4koib88HNTp1idmU6wlMTfDi6MzdX1GTC5nvenjut3XU
            8gl+ZOMXqL6psjJ4Ck73OU4MMN2e4d3HMXdluQIDAQABAoIBABBeq78EHQdp6Flo
            lXBqoiFMZa2g/dnKsFzH0R46V/KEQYJpQ3JfsPb8CCRYUy5GKwJXXXW7mYYhuSGV
            tIkxcJWfWHPTG4UQrFUb2nE/n2oiyUuityjeU4i+ei8shHgXJtoR/djbz22YceSD
            koo5NnwIfPJhx1usM22ku+geLXIThsDwLDXYzocTLGRlznl2Dl3ptA+pCDlYOfKN
            Fw1uzyn9o7WiFKoZeZms3UKV8MYYyjulWx0YkP6Vi6bXMoK1LwOf28K1+Oy61k6h
            9N2O32OjMwI+89ZKDBcNZxcUkf6M8g/auVNYD/i/dAdqUt6GC9UwO3i1fPNJEgdr
            KKKqWlkCgYEA+iG49x1ldTGPtp81S/sCSmyEYJCut/Fq+u5smhebs4tL5A2l0DyL
            mQv043YpZ+nt+0t4ROaVjT5JdJVb4N2Gy+J6rHxHygUZ8L2oqFu22cwC9+Onk0Yz
            IbXsXCjs+UuleXToA7Ex0wFcseJUZzqtBJrYVj9GStABZDcg4sf9xMUCgYEAxW2k
            ebo9o0fvSwt3ZbV9Z3NFxiHZ+kBNrJKzXt6CR5XRBX1xdSGUL2q/ecumWy+ULaK8
            iKuC2IjjB4VaOo+YYqUrKTjF8mFfxKAz2lJCQ7GmzyigE0E0bMiO+xG8FE3yEDVs
            C6nGUwqevC5KTRtlB2Pz5OXTHKUNyjD6Vz5p9GUCgYAXmyEiqTKPCdtfR80223yO
            24juuBjVIUKQZfn33OyD2EyUPDl+2ofuLLOy1872kJw2EBxMnFpW1x8Fkqb2JNH4
            4enj52K7DRoynyOQp/8stNU+4cxJ2OEweEPTOsWKjXoTaVYQKyPhnwpJe2utxlrX
            yWlLUnNm0hSfiZhf7rHjZQKBgB9Db4o/LcePeps9o5idltAs9t2bOrNgP2yWhoT9
            Y1AGr2TZKoBL4vVnSA73as6ByEs5u/VAg6Xad2kXeuRPHOhyE1WhwebR+KJgZBWs
            dQXXOf0QB9lEuBKJ5+pmMoxck3pxmzx1lAxOYDiYc/el4Oe8skCLDFU65eYgv2PV
            ZF3pAoGBAJ8XBtBw1zCZo84MLEu8NkWgQG0aa3uE3zpkWuDTOkE4wGK3QGUYnwLW
            fJeDQLS3KShOUWInn1sRRV147ScQeyIQUX3IXnpsguEanJU69h3Rz/lUxF4jIhS/
            1KlwnXkPq55CNx+7OiheTnUPvYLIzw+gOeWy8TsuukndMFY0XZ7a
            -----END RSA PRIVATE KEY-----
            """;

    private static final String PKCS8_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDA5xRmdT4M+k3m
            CRtpTHWPM/HS3+pmz75tg/FFsFQvDYLfg81xCOwrOQPQnB1NmdMkugwQhxywFDg3
            vFchq52LCFunsVlsNENhBLKO1h6ZbgKkpwlBjBEsNosK9vBDL/wBa3R5Uj9D0gmq
            IMbAWeJ1p0B1x7FDHZRTsP2TmfnMLMd5xe/666JglGjHYkeOC9PX6+/A4HisUmkT
            ks0MkuFpdteyKU5CiP7bHnZRilF5dmQlZrZYkK4ZaNeKB57ooZjaIQfiSiJvzwc1
            OnWJ2ZTrCUxN8OLozN1fUZMLme96eO63ddTyCX5k4xeovqmyMngKTvc5Tgww3Z7h
            3ccxd2W5AgMBAAECggEAEF6rvwQdB2noWWiVcGqiIUxlraD92cqwXMfRHjpX8oRB
            gmlDcl+w9vwIJFhTLkYrAldddbuZhiG5IZW0iTFwlZ9Yc9MbhRCsVRvacT+faiLJ
            S6K3KN5TiL56LyyEeBcm2hH92NvPbZhx5IOSijk2fAh88mHHW6wzbaS76B4tchOG
            wPAsNdjOhxMsZGXOeXYOXem0D6kIOVg58o0XDW7PKf2jtaIUqhl5mazdQpXwxhjK
            O6VbHRiQ/pWLptcygrUvA5/bwrX47LrWTqH03Y7fY6MzAj7z1koMFw1nFxSR/ozy
            D9q5U1gP+L90B2pS3oYL1TA7eLV880kSB2sooqpaWQKBgQD6Ibj3HWV1MY+2nzVL
            +wJKbIRgkK638Wr67myaF5uzi0vkDaXQPIuZC/Tjdiln6e37S3hE5pWNPkl0lVvg
            3YbL4nqsfEfKBRnwvaioW7bZzAL346eTRjMhtexcKOz5S6V5dOgDsTHTAVyx4lRn
            Oq0EmthWP0ZK0AFkNyDix/3ExQKBgQDFbaR5uj2jR+9LC3dltX1nc0XGIdn6QE2s
            krNe3oJHldEFfXF1IZQvar95y6ZbL5QtoryIq4LYiOMHhVo6j5hipSspOMXyYV/E
            oDPaUkJDsabPKKATQTRsyI77EbwUTfIQNWwLqcZTCp68LkpNG2UHY/Pk5dMcpQ3K
            MPpXPmn0ZQKBgBebISKpMo8J219HzTbbfI7biO64GNUhQpBl+ffc7IPYTJQ8OX7a
            h+4ss7LXzvaQnDYQHEycWlbXHwWSpvYk0fjh6ePnYrsNGjKfI5Cn/yy01T7hzEnY
            4TB4Q9M6xYqNehNpVhArI+GfCkl7a63GWtfJaUtSc2bSFJ+JmF/useNlAoGAH0Nv
            ij8tx496mz2jmJ2W0Cz23Zs6s2A/bJaGhP1jUAavZNkqgEvi9WdIDvdqzoHISzm7
            9UCDpdp3aRd65E8c6HITVaHB5tH4omBkFax1Bdc5/RAH2US4Eonn6mYyjFyTenGb
            PHWUDE5gOJhz96Xg57yyQIsMVTrl5iC/Y9VkXekCgYEAnxcG0HDXMJmjzgwsS7w2
            RaBAbRpre4TfOmRa4NM6QTjAYrdAZRifAtZ8l4NAtLcpKE5RYiefWxFFXXjtJxB7
            IhBRfcheemyC4RqclTr2HdHP+VTEXiMiFL/UqXCdeQ+rnkI3H7s6KF5OdQ+9gsjP
            D6A55bLxOy66Sd0wVjRdnto=
            -----END PRIVATE KEY-----
            """;

    @Test
    void parsesPkcs8PemRsaKey() {
        final PrivateKey key = Certs.parsePrivateKey(PKCS8_PEM.getBytes(StandardCharsets.US_ASCII));

        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void parsesPkcs1PemRsaKey_yieldingTheSameKeyAsPkcs8() {
        final PrivateKey fromPkcs1 = Certs.parsePrivateKey(PKCS1_PEM.getBytes(StandardCharsets.US_ASCII));
        final PrivateKey fromPkcs8 = Certs.parsePrivateKey(PKCS8_PEM.getBytes(StandardCharsets.US_ASCII));

        assertThat(fromPkcs1.getAlgorithm()).isEqualTo("RSA");
        // The wrapper is correct iff the PKCS#1 and PKCS#8 forms decode to byte-identical PKCS#8.
        assertThat(fromPkcs1.getEncoded()).isEqualTo(fromPkcs8.getEncoded());
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Certs.parsePrivateKey("not a key".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesP384CaKey() {
        assertThat(CertFixtures.CA_A_KEY.getAlgorithm()).isEqualTo("EC");
    }

    @Test
    void chainsTo_acceptsLeafUnderItsOwnCa() {
        final X509Certificate leaf = CertFixtures.leafUnderA().cert();

        assertThat(Certs.chainsTo(leaf, CertFixtures.CA_A_CERT)).isTrue();
    }

    @Test
    void chainsTo_rejectsLeafUnderADifferentCa() {
        final X509Certificate leaf = CertFixtures.leafUnderA().cert();

        assertThat(Certs.chainsTo(leaf, CertFixtures.CA_B_CERT)).isFalse();
    }

    @Test
    void matchRosterByCa_findsTheIssuingEndpoint() {
        final X509Certificate leaf = CertFixtures.leafUnderA().cert();
        final var issuer = CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER);
        final var other = CertFixtures.endpointWithCert(CertFixtures.CA_B_CERT_DER);

        assertThat(Certs.matchRosterByCa(leaf, List.of(PeerRosterEntry.parse(other), PeerRosterEntry.parse(issuer))))
                .contains(issuer);
    }

    @Test
    void matchRosterByCa_emptyWhenNoRosterCaIssuedTheLeaf() {
        final X509Certificate leaf = CertFixtures.leafUnderA().cert();

        assertThat(Certs.matchRosterByCa(
                        leaf,
                        List.of(PeerRosterEntry.parse(CertFixtures.endpointWithCert(CertFixtures.CA_B_CERT_DER)))))
                .isEmpty();
    }

    @Test
    void matchRosterByCa_rejectsASelfSignedCertNotIssuedByAnyRosterCa() {
        // An attacker presents their own self-signed certificate (CA_B's cert stands in for it here).
        // It was not signed by CA_A, so it must not chain to CA_A and must not match a CA_A-only roster.
        final X509Certificate attacker = CertFixtures.CA_B_CERT;

        assertThat(Certs.chainsTo(attacker, CertFixtures.CA_A_CERT)).isFalse();
        assertThat(Certs.matchRosterByCa(
                        attacker,
                        List.of(PeerRosterEntry.parse(CertFixtures.endpointWithCert(CertFixtures.CA_A_CERT_DER)))))
                .isEmpty();
    }

    @Test
    void requireEcP384_rejectsANonP384Key() throws Exception {
        // A P-256 key signs SHA384withECDSA successfully but would mint a leaf that can't chain to a
        // P-384 on-chain CA — the guard must reject it up front rather than let it fail silently at a peer.
        final var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final PrivateKey wrongCurve = kpg.generateKeyPair().getPrivate();

        assertThatThrownBy(() -> Certs.requireEcP384(wrongCurve))
                .isInstanceOf(InvalidKeyException.class)
                .hasMessageContaining("P-384");
    }

    @Test
    void requireEcP384_acceptsAP384Key() throws Exception {
        // A P-384 CA key is exactly what the leaf is signed by; the guard must let it through.
        Certs.requireEcP384(CertFixtures.CA_A_KEY);
    }
}
