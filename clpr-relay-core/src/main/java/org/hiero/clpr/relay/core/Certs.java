// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for CLPR mTLS certificate trust.
 *
 * <p>Trust works by CA chain validation, not by pinning an exact certificate. Each endpoint publishes an
 * ECDSA P-384 <b>CA</b> certificate on-chain in {@code ClprEndpoint.tls_certificate}; at the handshake it
 * presents an Ed25519 <b>leaf</b> signed by that CA, and a peer accepts the leaf when it chains to the
 * CA. The on-chain certificate is trusted as-is (it is authoritative ledger state); only the presented
 * leaf is verified.
 */
public final class Certs {

    /** DER of the {@code rsaEncryption} AlgorithmIdentifier ({@code OID 1.2.840.113549.1.1.1}, NULL params). */
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
        0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    /**
     * Standard signature algorithms probed by {@link #keyMatchesCertificate}. The algorithm whose
     * {@code initSign} accepts the key is the one used — the JDK performs the key-type match, so no
     * explicit key-type→algorithm mapping is maintained here.
     */
    private static final String[] PROBE_SIGNATURE_ALGORITHMS = {"SHA256withRSA", "SHA256withECDSA", "Ed25519"};

    /** Bit length of the P-384 (secp384r1) curve's prime field — the required CA key strength. */
    private static final int P384_FIELD_BITS = 384;

    private Certs() {}

    /**
     * Parse exactly one X.509 certificate from DER bytes.
     *
     * @param der the DER encoding of a single X.509 certificate
     * @return the parsed certificate; never {@code null}
     * @throws CertificateException if the bytes are not a single well-formed X.509 certificate
     *     (empty input, a chain of more than one, or trailing/garbage bytes)
     */
    public static X509Certificate parse(final byte[] der) throws CertificateException {
        final CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(der));
        if (certs.size() != 1) {
            throw new CertificateException("expected exactly one X.509 certificate, got " + certs.size());
        }
        return (X509Certificate) certs.iterator().next();
    }

    /**
     * Returns {@code true} iff {@code leaf} satisfies all three conditions:
     * <ol>
     *   <li>it is not itself a CA certificate ({@code basicConstraints} absent or {@code CA:FALSE}),</li>
     *   <li>the current time falls within its validity window, and</li>
     *   <li>its signature verifies against {@code caCert}'s public key.</li>
     * </ol>
     *
     * <p>{@code caCert} is the trust anchor trusted as-is
     * No PKIX path validation is performed and {@code caCert}'s own extensions are not
     * re-checked.
     *
     * <p>Returns {@code false} on any failure rather than throwing, so callers get a plain yes/no
     * answer without having to handle a reason.
     *
     * @param leaf   the presented leaf certificate
     * @param caCert the CA certificate obtained from the on-chain roster entry
     * @return {@code true} if {@code leaf} was issued by {@code caCert} and is currently valid
     */
    public static boolean chainsTo(final X509Certificate leaf, final X509Certificate caCert) {
        // Issuer/subject DN check dropped intentionally — verify(caCert.getPublicKey()) is the meaningful gate.
        // TODO(leaf-validation): Once we load the full CA certificate from chain we can extract the issuer DN,
        // generate the leaf with a matching issuer, and validate issuer==subject here. When restoring this check,
        // ensure ENDPOINT_DN in LeafCertificate.java aligns with the on-chain CA subject DN.
        try {
            if (leaf.getBasicConstraints() >= 0) return false; // reject CA-as-leaf
            leaf.checkValidity();
            leaf.verify(caCert.getPublicKey());
            return true;
        } catch (final GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Find the roster endpoint whose pre-parsed TLS CA certificate issued {@code leaf}, validated by
     * {@link #chainsTo}.
     *
     * <p>A roster entry whose {@link PeerRosterEntry#tlsCert()} is {@code null} (no certificate
     * published, or a malformed one) is skipped — it can never issue a real leaf. This method only
     * reports a positive identity, never a failure reason.
     *
     * @param leaf    the presented leaf certificate
     * @param entries the pre-parsed roster entries to match against
     * @return the first endpoint whose CA issued {@code leaf}, or empty if none match
     */
    public static Optional<ClprEndpoint> matchRosterByCa(
            @Nullable final X509Certificate leaf, final Iterable<PeerRosterEntry> entries) {
        for (final PeerRosterEntry entry : entries) {
            if (entry.tlsCert() != null && chainsTo(leaf, entry.tlsCert())) {
                return Optional.of(entry.endpoint());
            }
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of the input bytes as lower-case hexadecimal.
     *
     * @param bytes the input bytes
     * @return the SHA-256 digest as lower-case hexadecimal
     */
    public static String sha256Hex(final byte[] bytes) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            final var sb = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e); // unreachable on a conformant JDK
        }
    }

    /**
     * Parse a PEM {@code RSA PRIVATE KEY} (PKCS#1) block, or a raw DER PKCS#8 encoding.
     *
     * @param keyBytes the PEM or DER key bytes
     * @return the parsed private key
     * @throws IllegalArgumentException if the bytes are not a supported RSA or EC private key
     */
    public static PrivateKey parsePrivateKey(final byte[] keyBytes) {
        final var spec = new PKCS8EncodedKeySpec(pkcs8Der(keyBytes));
        for (final String algorithm : new String[] {"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (final GeneralSecurityException tryNext) {
                // not this algorithm
            }
        }
        throw new IllegalArgumentException("not a supported RSA or EC private key");
    }

    /**
     * Fail fast unless {@code caKey} is an ECDSA key on the P-384 (secp384r1) curve.
     *
     * @param caKey the CA private key to check
     * @throws InvalidKeyException if {@code caKey} is not an ECDSA P-384 key
     */
    public static void requireEcP384(final PrivateKey caKey) throws InvalidKeyException {
        final int fieldSize = (caKey instanceof ECKey ec)
                ? ec.getParams().getCurve().getField().getFieldSize()
                : -1;
        if (fieldSize != P384_FIELD_BITS) {
            throw new InvalidKeyException("the mTLS CA key must be an ECDSA P-384 key, but got " + caKey.getAlgorithm()
                    + (fieldSize > 0 ? " on a " + fieldSize + "-bit curve" : ""));
        }
    }

    /**
     * Whether {@code key} is the private half of {@code cert}'s public key, tested via a sign/verify
     * round-trip. The signature algorithm is discovered by trying {@link #PROBE_SIGNATURE_ALGORITHMS}
     * and using whichever one the key accepts, rather than mapping key types to algorithm names.
     *
     * @param key  the private key
     * @param cert the certificate whose public key is checked against {@code key}
     * @return {@code true} if the key matches the certificate
     */
    public static boolean keyMatchesCertificate(final PrivateKey key, final X509Certificate cert) {
        final byte[] probe = "clpr-tls-key-certificate-check".getBytes(StandardCharsets.US_ASCII);
        final PublicKey publicKey = cert.getPublicKey();
        for (final String signatureAlgorithm : PROBE_SIGNATURE_ALGORITHMS) {
            final Signature signer;
            try {
                signer = Signature.getInstance(signatureAlgorithm);
                signer.initSign(key);
            } catch (final GeneralSecurityException notThisAlgorithm) {
                continue; // this algorithm does not accept the key's type — try the next
            }
            try {
                signer.update(probe);
                final byte[] signature = signer.sign();
                final Signature verifier = Signature.getInstance(signatureAlgorithm);
                verifier.initVerify(publicKey);
                verifier.update(probe);
                return verifier.verify(signature);
            } catch (final GeneralSecurityException differentKeyType) {
                // The key signed but the certificate's public key could not verify with this algorithm,
                // so the key and certificate are of different key types — not a matching pair.
                return false;
            }
        }
        return false; // no probed signature algorithm accepted the key
    }

    /**
     * Return PKCS#8 DER for the input. A PEM {@code PRIVATE KEY} block is decoded as-is; a PEM
     * {@code RSA PRIVATE KEY} (PKCS#1) block is decoded and wrapped into PKCS#8; raw (non-PEM) bytes are
     * assumed to already be PKCS#8 DER.
     */
    private static byte[] pkcs8Der(final byte[] bytes) {
        final String text = new String(bytes, StandardCharsets.US_ASCII);
        if (!text.contains("-----BEGIN")) {
            return bytes; // raw DER — assumed PKCS#8
        }
        final boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
        final String base64 = text.replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        final byte[] der = Base64.getDecoder().decode(base64);
        return pkcs1 ? wrapPkcs1AsPkcs8(der) : der;
    }

    /**
     * Wrap a PKCS#1 {@code RSAPrivateKey} DER into a PKCS#8 {@code PrivateKeyInfo} DER:
     * {@code SEQUENCE { INTEGER 0, rsaEncryption AlgorithmIdentifier, OCTET STRING(pkcs1) }}.
     */
    private static byte[] wrapPkcs1AsPkcs8(final byte[] pkcs1) {
        final byte[] version = {0x02, 0x01, 0x00}; // INTEGER 0
        final byte[] privateKey = derTagged(0x04, pkcs1); // OCTET STRING wrapping the PKCS#1 key
        final byte[] body = concat(version, RSA_ALGORITHM_IDENTIFIER, privateKey);
        return derTagged(0x30, body); // SEQUENCE
    }

    /** Encode a single DER TLV: {@code tag || length || content}, with definite long-form length as needed. */
    private static byte[] derTagged(final int tag, final byte[] content) {
        final byte[] length = derLength(content.length);
        final byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    /** DER definite length: short form ({@code < 128}) or long form ({@code 0x8n} + big-endian bytes). */
    private static byte[] derLength(final int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        int remaining = length;
        int numBytes = 0;
        while (remaining > 0) {
            numBytes++;
            remaining >>>= 8;
        }
        final byte[] out = new byte[1 + numBytes];
        out[0] = (byte) (0x80 | numBytes);
        for (int i = 0; i < numBytes; i++) {
            out[out.length - 1 - i] = (byte) (length >>> (8 * i));
        }
        return out;
    }

    /** Concatenate byte arrays. */
    private static byte[] concat(final byte[]... parts) {
        int total = 0;
        for (final byte[] part : parts) {
            total += part.length;
        }
        final byte[] out = new byte[total];
        int offset = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
