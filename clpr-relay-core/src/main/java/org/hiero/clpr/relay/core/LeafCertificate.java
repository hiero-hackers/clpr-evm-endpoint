// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * This endpoint's TLS leaf certificate and its matching private key. The relay presents this at every
 * mTLS handshake to prove who it is.
 *
 * <p>The certificate is an Ed25519 certificate signed by this endpoint's CA — the ECDSA P-384 key whose
 * certificate is published on-chain in {@code ClprEndpoint.tls_certificate}. It is created fresh when the
 * process starts, kept only in memory, and never written to disk or published anywhere. A peer trusts it
 * because it is signed by a CA the peer already knows from the roster.
 *
 * <p>The leaf's validity window is supplied by the caller. {@link LeafKeyManager} re-mints the leaf on
 * demand when the window elapses, so at-rest expiry does not cause handshake failures. The only way to
 * stop trusting a leaf is to remove its CA from the on-chain roster.
 *
 * @param cert the certificate to present on the wire
 * @param key  the private key that matches {@code cert}
 */
public record LeafCertificate(X509Certificate cert, PrivateKey key) {

    /** Signature algorithm for the CA's signature over the leaf: CNSA-aligned ECDSA P-384 + SHA-384. */
    private static final String CA_SIGNATURE_ALGORITHM = "SHA384withECDSA";

    /** Backdate {@code notBefore} and extend {@code notAfter} to tolerate modest clock skew. */
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    /**
     * The fixed distinguished name every endpoint's CA and leaf certificate share, used here as both the
     * issuer and subject of the generated leaf.
     *
     * <p>This value must match the subject DN of the CA certificates already published on-chain.
     */
    public static final String ENDPOINT_DN = "CN=CLPR";

    /**
     * Generate a fresh Ed25519 leaf signed by the given ECDSA P-384 CA private key, with the supplied
     * validity window.
     *
     * <p>The leaf carries serial {@code 1}, {@code basicConstraints CA:FALSE} and {@code keyUsage
     * digitalSignature} (both critical), and both its issuer and subject are {@link #ENDPOINT_DN} so that
     * standard PKIX path validation against the endpoint's on-chain CA (whose subject is the same DN)
     * accepts it. The CA certificate itself is never read — only its key is needed to sign.
     *
     * <p>{@code notBefore} is backdated by {@code CLOCK_SKEW} to tolerate modest clock differences at
     * validation time; {@code notAfter} is set to {@code now + validity + CLOCK_SKEW} so that an in-flight
     * handshake remains valid for a full {@code CLOCK_SKEW} after rotation fires.
     *
     * @param caKey    the endpoint's CA private key (ECDSA P-384), used to sign the leaf; its public half
     *                 must be the one in the CA certificate published on-chain
     * @param validity how long the cert should be considered active; the actual {@code notAfter} includes
     *                 an extra {@code CLOCK_SKEW} tail buffer
     * @return a freshly generated leaf certificate and its private key
     * @throws GeneralSecurityException if key generation or certificate construction fails
     */
    public static LeafCertificate generate(final PrivateKey caKey, final Duration validity)
            throws GeneralSecurityException {
        final KeyPair leafKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Instant now = Instant.now();
        final Date notBefore = Date.from(now.minus(CLOCK_SKEW));
        final Date notAfter = Date.from(now.plus(validity).plus(CLOCK_SKEW));

        try {
            final var dn = new X500Name(ENDPOINT_DN);
            final var builder = new JcaX509v3CertificateBuilder(
                    dn, BigInteger.ONE, notBefore, notAfter, dn, leafKeyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));

            final ContentSigner signer = new JcaContentSignerBuilder(CA_SIGNATURE_ALGORITHM).build(caKey);
            final X509Certificate leaf = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            return new LeafCertificate(leaf, leafKeyPair.getPrivate());
        } catch (final CertIOException | OperatorCreationException e) {
            throw new GeneralSecurityException("failed to build the mTLS leaf certificate: " + e.getMessage(), e);
        }
    }
}
