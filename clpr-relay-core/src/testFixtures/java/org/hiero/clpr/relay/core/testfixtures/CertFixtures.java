// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.testfixtures;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import org.hiero.clpr.relay.core.Certs;
import org.hiero.clpr.relay.core.LeafCertificate;
import org.jspecify.annotations.NullMarked;

/**
 * mTLS certificate fixtures for the two-tier trust model: independent self-signed ECDSA P-384 CA roots
 * (each with its private key) plus helpers to mint Ed25519 leaves under them.
 *
 * <p>{@link #CA_A_CERT} and {@link #CA_B_CERT} are fixed minimized certificates (subject
 * {@code CN=CLPR} — the shared {@link LeafCertificate#ENDPOINT_DN}, {@code CA:TRUE},
 * {@code keyCertSign,cRLSign}, ~100-year validity). Leaves are minted fresh per call via the production
 * {@link CertFixtures#generate} path; a leaf signed by {@link #CA_A_KEY} chains to {@link #CA_A_CERT}
 * and not to {@link #CA_B_CERT} — the two CAs share a subject but differ by key, so identity is by which
 * key signed the leaf.
 */
public final class CertFixtures {

    private CertFixtures() {}

    private static final String CA_A_CERT_B64 =
            "MIIBwTCCAUigAwIBAgIUZoDFy8ZBzlZPGFv7znW8Qne+6HUwCgYIKoZIzj0EAwMwDzENMAsGA1UEAwwEQ0xQUjAgFw0yNjA3MjcxNzExMjRaGA8yMTI2MDcwMzE3MTEyNFowDzENMAsGA1UEAwwEQ0xQUjB2MBAGByqGSM49AgEGBSuBBAAiA2IABDYvPPc/LKpQRoojBKsiliflEOu810wWB4jVF4XKzVbYy2O7/MVheI5YIv+oJOHu7qPtQqTKPhFYtseCgCtfbGk4Wbau1CQXvebi5HC13tVdnZT0iosa6B5nshz9lJMDVaNjMGEwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFBRkaL+AB8U297v80FPMM6WNQ/NZMB8GA1UdIwQYMBaAFBRkaL+AB8U297v80FPMM6WNQ/NZMAoGCCqGSM49BAMDA2cAMGQCMCSa/aaBZHYmniVusu5VQADLc/BxWYKTxNF6gDJS+KdrExlHcqP0XnilwSHyrBfTzwIwDMickkhOkzgxxocUICayvjjwjtUQ7/Zj3+cTEqpAvx2SQy3cVM85dwSydOAmLRLF";
    private static final String CA_A_KEY_B64 =
            "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDBSyLD+7T0owrGEYn6QTffKCcQh0jPGvjcr5DBC7fk3s8b724ZEnDlx2AWb8r8H6nChZANiAAQ2Lzz3PyyqUEaKIwSrIpYn5RDrvNdMFgeI1ReFys1W2Mtju/zFYXiOWCL/qCTh7u6j7UKkyj4RWLbHgoArX2xpOFm2rtQkF73m4uRwtd7VXZ2U9IqLGugeZ7Ic/ZSTA1U=";
    private static final String CA_B_CERT_B64 =
            "MIIBwzCCAUigAwIBAgIUYSel4RVIyw13eNEVeufotrFdic4wCgYIKoZIzj0EAwMwDzENMAsGA1UEAwwEQ0xQUjAgFw0yNjA3MjcxNzExMjRaGA8yMTI2MDcwMzE3MTEyNFowDzENMAsGA1UEAwwEQ0xQUjB2MBAGByqGSM49AgEGBSuBBAAiA2IABGXDegQolBNgY6OkMEcNUSSASL8Zt79rtmJP60ggrE7MKRBe2DEreYti0mHwYQKQsf9nY1XpQi3N98c/HZEoEC7+G3HAWFTDmlkd3nOR5tVSxKwq5YQHaah2ooiySrqZf6NjMGEwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFD2Aj0ESkJNRzuNglad8u4ip4ts8MB8GA1UdIwQYMBaAFD2Aj0ESkJNRzuNglad8u4ip4ts8MAoGCCqGSM49BAMDA2kAMGYCMQD99u238o2RZ55kokjantbtN8wF52PwMfF7L0A58ImzbdgksnpOB1YniZgeFYeTdzkCMQDZVNn9XDvhL8hDtsbu6MmP4k6jM2/0dsMhD8SqIkV05IMoCxi49qriapZGHcJ7mGI=";
    private static final String CA_B_KEY_B64 =
            "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDA3qZrnSJUGQGIUXEn2enGKrMm135zTLTlKFpo2crpQKvGfdxQoneOYKI7W2HSuFwuhZANiAARlw3oEKJQTYGOjpDBHDVEkgEi/Gbe/a7ZiT+tIIKxOzCkQXtgxK3mLYtJh8GECkLH/Z2NV6UItzffHPx2RKBAu/htxwFhUw5pZHd5zkebVUsSsKuWEB2modqKIskq6mX8=";

    /** DER of CA root A's certificate. */
    public static final byte[] CA_A_CERT_DER = Base64.getDecoder().decode(CA_A_CERT_B64);
    /** DER of CA root B's certificate. */
    public static final byte[] CA_B_CERT_DER = Base64.getDecoder().decode(CA_B_CERT_B64);

    /** PKCS#8 DER of CA root A's private key, for tests that stage the CA key on disk. */
    public static final byte[] CA_A_KEY_DER = Base64.getDecoder().decode(CA_A_KEY_B64);
    /** PKCS#8 DER of CA root B's private key, for tests that stage the CA key on disk. */
    public static final byte[] CA_B_KEY_DER = Base64.getDecoder().decode(CA_B_KEY_B64);

    /** CA root A's certificate. */
    public static final X509Certificate CA_A_CERT = parseCert(CA_A_CERT_DER);
    /** CA root B's certificate. */
    public static final X509Certificate CA_B_CERT = parseCert(CA_B_CERT_DER);

    /** CA root A's private key (ECDSA P-384). */
    public static final PrivateKey CA_A_KEY = parseKey(CA_A_KEY_B64);
    /** CA root B's private key (ECDSA P-384). */
    public static final PrivateKey CA_B_KEY = parseKey(CA_B_KEY_B64);

    /** Mint a fresh Ed25519 leaf signed by CA root A. */
    public static LeafCertificate leafUnderA() {
        return leaf(CA_A_KEY);
    }

    /** Mint a fresh Ed25519 leaf signed by CA root B. */
    public static LeafCertificate leafUnderB() {
        return leaf(CA_B_KEY);
    }

    /** Mint a fresh Ed25519 leaf signed by the given CA private key. */
    public static LeafCertificate leaf(final PrivateKey caKey) {
        try {
            return generate(caKey);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("failed to mint a test leaf certificate", e);
        }
    }

    /** A peer endpoint carrying the given DER as its {@code tls_certificate}. */
    public static ClprEndpoint endpointWithCert(final byte[] der) {
        return ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                .tlsCertificate(Bytes.wrap(der))
                .build();
    }

    private static X509Certificate parseCert(final byte[] der) {
        try {
            return Certs.parse(der);
        } catch (final CertificateException e) {
            throw new IllegalStateException("invalid CA certificate fixture", e);
        }
    }

    private static PrivateKey parseKey(final String base64) {
        return Certs.parsePrivateKey(Base64.getDecoder().decode(base64));
    }

    /**
     * Generate a fresh Ed25519 leaf with a long-lived validity window suitable for a non-rotating
     * process lifetime. Equivalent to {@link #generate(PrivateKey, Duration)} with a 10-year window.
     *
     * @param caKey the endpoint's CA private key (ECDSA P-384)
     * @return a freshly generated leaf certificate and its private key
     * @throws GeneralSecurityException if key generation or certificate construction fails
     */
    @NullMarked
    public static LeafCertificate generate(final PrivateKey caKey) throws GeneralSecurityException {
        return LeafCertificate.generate(caKey, Duration.ofDays(3650));
    }
}
