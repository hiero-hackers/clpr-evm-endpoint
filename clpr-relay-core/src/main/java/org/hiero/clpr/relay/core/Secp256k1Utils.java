// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import java.math.BigInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Pure-Java secp256k1 ECDSA signing and public-key recovery utilities.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #keccak256(byte[])} — Keccak-256 digest (Ethereum flavour, padding byte 0x01)</li>
 *   <li>{@link #sign(byte[], byte[])} — deterministic ECDSA sign (RFC 6979, low-S canonical form)
 *       returning a 65-byte {@code r||s||v} recoverable signature (v = 0 or 1)</li>
 *   <li>{@link #recoverPublicKey(byte[], byte[])} — recover the uncompressed 64-byte public key
 *       (X||Y, no 0x04 prefix) from a 65-byte recoverable signature and its original digest</li>
 *   <li>{@link #deriveUncompressedPublicKey(byte[])} — derive the 64-byte uncompressed public key
 *       from a 32-byte private key</li>
 * </ul>
 *
 * <p>No external dependencies are required; all elliptic-curve arithmetic is implemented in
 * pure Java using {@link BigInteger}.
 */
public final class Secp256k1Utils {

    // -------------------------------------------------------------------------
    // secp256k1 curve parameters (SECG SEC 2, §2.4.1)
    // -------------------------------------------------------------------------

    /** Field prime p. */
    public static final BigInteger P =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);

    /** Curve order n. */
    public static final BigInteger N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    /** Generator x-coordinate. */
    private static final BigInteger GX =
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);

    /** Generator y-coordinate. */
    private static final BigInteger GY =
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);

    private Secp256k1Utils() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Compute the Keccak-256 digest of {@code input}.
     *
     * <p>This is the Ethereum-flavour Keccak (padding byte {@code 0x01}), not SHA3-256.
     *
     * @param input the message to hash (may be empty)
     * @return a 32-byte digest
     */
    @NonNull
    public static byte[] keccak256(@NonNull final byte[] input) {
        return Keccak256.keccak256(input);
    }

    /**
     * Compute the Keccak-256 digest over the concatenation of {@code a} and {@code b}.
     *
     * @param a first segment
     * @param b second segment
     * @return a 32-byte digest
     */
    @NonNull
    public static byte[] keccak256(@NonNull final byte[] a, @NonNull final byte[] b) {
        final byte[] concat = new byte[a.length + b.length];
        System.arraycopy(a, 0, concat, 0, a.length);
        System.arraycopy(b, 0, concat, a.length, b.length);
        return Keccak256.keccak256(concat);
    }

    /**
     * Sign a 32-byte digest with a secp256k1 private key using RFC 6979 deterministic k.
     *
     * <p>The signature is in low-S canonical form (BIP-62): if the computed {@code s > n/2}
     * it is replaced with {@code n - s} and the recovery id is toggled accordingly.
     *
     * @param digest     the 32-byte message digest to sign
     * @param privateKey the 32-byte private key
     * @return a 65-byte recoverable signature {@code r(32) || s(32) || v(1)}, where v = 0 or 1
     * @throws IllegalArgumentException if {@code digest} or {@code privateKey} is not 32 bytes,
     *                                  or if the private key is out of the valid secp256k1 range
     */
    @NonNull
    public static byte[] sign(@NonNull final byte[] digest, @NonNull final byte[] privateKey) {
        if (digest.length != 32) {
            throw new IllegalArgumentException("Digest must be 32 bytes, got " + digest.length);
        }
        if (privateKey.length != 32) {
            throw new IllegalArgumentException("Private key must be 32 bytes, got " + privateKey.length);
        }
        final BigInteger privInt = new BigInteger(1, privateKey);
        if (privInt.signum() <= 0 || privInt.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Private key is out of range for secp256k1");
        }

        final BigInteger z = new BigInteger(1, digest);
        BigInteger k = Rfc6979.generate(privateKey, digest);
        final BigInteger[] kPoint = pointMultiply(GX, GY, k);
        BigInteger r = kPoint[0].mod(N);
        if (r.signum() == 0) {
            throw new IllegalStateException("RFC 6979 produced r=0; key or hash is degenerate");
        }
        BigInteger s = k.modInverse(N).multiply(z.add(r.multiply(privInt))).mod(N);

        // Normalise to low-S (BIP-62)
        boolean lowS = s.compareTo(N.shiftRight(1)) <= 0;
        if (!lowS) {
            s = N.subtract(s);
        }

        // Compute recovery id: which candidate (0 or 1) yields our public key
        final BigInteger[] expectedPub = pointMultiply(GX, GY, privInt);
        int v = -1;
        for (int recId = 0; recId < 2; recId++) {
            final BigInteger[] candidate = recoverPublicKeyFromComponents(r, s, z, recId);
            if (candidate != null && candidate[0].equals(expectedPub[0]) && candidate[1].equals(expectedPub[1])) {
                v = recId;
                break;
            }
        }
        if (v < 0) {
            throw new IllegalStateException("Could not determine recovery id for signature");
        }

        final byte[] result = new byte[65];
        final byte[] rBytes = bigIntTo32Bytes(r);
        final byte[] sBytes = bigIntTo32Bytes(s);
        System.arraycopy(rBytes, 0, result, 0, 32);
        System.arraycopy(sBytes, 0, result, 32, 32);
        result[64] = (byte) v;
        return result;
    }

    /**
     * Recover the uncompressed 64-byte secp256k1 public key (X||Y, no 0x04 prefix) from a
     * 65-byte recoverable signature and the original 32-byte digest.
     *
     * @param signature65 the 65-byte signature {@code r(32) || s(32) || v(1)}
     * @param digest      the 32-byte digest that was signed
     * @return the 64-byte uncompressed public key (X||Y), or {@code null} if recovery fails
     * @throws IllegalArgumentException if {@code signature65} is not exactly 65 bytes or
     *                                  {@code digest} is not exactly 32 bytes
     */
    @Nullable
    public static byte[] recoverPublicKey(@NonNull final byte[] signature65, @NonNull final byte[] digest) {
        if (signature65.length != 65) {
            throw new IllegalArgumentException("Signature must be 65 bytes, got " + signature65.length);
        }
        if (digest.length != 32) {
            throw new IllegalArgumentException("Digest must be 32 bytes, got " + digest.length);
        }
        final byte[] rBytes = new byte[32];
        final byte[] sBytes = new byte[32];
        System.arraycopy(signature65, 0, rBytes, 0, 32);
        System.arraycopy(signature65, 32, sBytes, 0, 32);
        final int v = signature65[64] & 0xff;
        if (v > 1) {
            return null; // unsupported recovery id
        }

        final BigInteger r = new BigInteger(1, rBytes);
        final BigInteger s = new BigInteger(1, sBytes);
        final BigInteger z = new BigInteger(1, digest);
        final BigInteger[] pubPoint = recoverPublicKeyFromComponents(r, s, z, v);
        if (pubPoint == null) {
            return null;
        }
        final byte[] pub = new byte[64];
        final byte[] xBytes = bigIntTo32Bytes(pubPoint[0]);
        final byte[] yBytes = bigIntTo32Bytes(pubPoint[1]);
        System.arraycopy(xBytes, 0, pub, 0, 32);
        System.arraycopy(yBytes, 0, pub, 32, 32);
        return pub;
    }

    /**
     * Derive the uncompressed 64-byte secp256k1 public key (X||Y, no 0x04 prefix) from a
     * 32-byte private key.
     *
     * @param privateKey the 32-byte private key
     * @return the 64-byte uncompressed public key
     * @throws IllegalArgumentException if {@code privateKey} is not 32 bytes or out of range
     */
    @NonNull
    public static byte[] deriveUncompressedPublicKey(@NonNull final byte[] privateKey) {
        if (privateKey.length != 32) {
            throw new IllegalArgumentException("Private key must be 32 bytes, got " + privateKey.length);
        }
        final BigInteger privInt = new BigInteger(1, privateKey);
        if (privInt.signum() <= 0 || privInt.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Private key is out of range for secp256k1");
        }
        final BigInteger[] pub = pointMultiply(GX, GY, privInt);
        final byte[] result = new byte[64];
        System.arraycopy(bigIntTo32Bytes(pub[0]), 0, result, 0, 32);
        System.arraycopy(bigIntTo32Bytes(pub[1]), 0, result, 32, 32);
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal: public-key recovery
    // -------------------------------------------------------------------------

    @Nullable
    static BigInteger[] recoverPublicKeyFromComponents(
            @NonNull final BigInteger r, @NonNull final BigInteger s, @NonNull final BigInteger z, final int recId) {
        final BigInteger x = r;
        final BigInteger y2 = x.pow(3).add(BigInteger.valueOf(7L)).mod(P);
        final BigInteger y = y2.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.multiply(y).mod(P).equals(y2)) {
            return null;
        }
        final BigInteger yFinal = (y.testBit(0) == ((recId & 1) == 1)) ? y : P.subtract(y);

        final BigInteger rInv = r.modInverse(N);
        final BigInteger[] sR = pointMultiply(x, yFinal, s);
        final BigInteger negZ = N.subtract(z.mod(N)).mod(N);
        final BigInteger[] negZG = pointMultiply(GX, GY, negZ);
        final BigInteger[] sum = pointAdd(sR[0], sR[1], negZG[0], negZG[1]);
        return pointMultiply(sum[0], sum[1], rInv);
    }

    // -------------------------------------------------------------------------
    // Internal: secp256k1 elliptic curve arithmetic (affine coordinates)
    // -------------------------------------------------------------------------

    @NonNull
    static BigInteger[] pointAdd(
            @NonNull final BigInteger x1,
            @NonNull final BigInteger y1,
            @NonNull final BigInteger x2,
            @NonNull final BigInteger y2) {
        if (x1.equals(x2) && y1.equals(y2)) {
            return pointDouble(x1, y1);
        }
        if (x1.equals(x2)) {
            return new BigInteger[] {GX, GY};
        }
        final BigInteger slope = y2.subtract(y1)
                .mod(P)
                .multiply(x2.subtract(x1).mod(P).modInverse(P))
                .mod(P);
        final BigInteger x3 = slope.multiply(slope).subtract(x1).subtract(x2).mod(P);
        final BigInteger y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(P);
        return new BigInteger[] {x3, y3};
    }

    @NonNull
    private static BigInteger[] pointDouble(@NonNull final BigInteger x, @NonNull final BigInteger y) {
        final BigInteger slope = BigInteger.valueOf(3L)
                .multiply(x.multiply(x))
                .multiply(BigInteger.TWO.multiply(y).modInverse(P))
                .mod(P);
        final BigInteger x3 =
                slope.multiply(slope).subtract(BigInteger.TWO.multiply(x)).mod(P);
        final BigInteger y3 = slope.multiply(x.subtract(x3)).subtract(y).mod(P);
        return new BigInteger[] {x3, y3};
    }

    @NonNull
    static BigInteger[] pointMultiply(
            @NonNull final BigInteger gx, @NonNull final BigInteger gy, @NonNull final BigInteger scalar) {
        BigInteger[] result = null;
        BigInteger[] addend = new BigInteger[] {gx, gy};
        BigInteger k = scalar.mod(N);
        while (k.signum() > 0) {
            if (k.testBit(0)) {
                result = (result == null) ? addend : pointAdd(result[0], result[1], addend[0], addend[1]);
            }
            addend = pointDouble(addend[0], addend[1]);
            k = k.shiftRight(1);
        }
        if (result == null) {
            throw new IllegalArgumentException("Scalar multiplication by zero");
        }
        return result;
    }

    @NonNull
    public static byte[] bigIntTo32Bytes(@NonNull final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Internal: RFC 6979 deterministic k generation
    // -------------------------------------------------------------------------

    private static final class Rfc6979 {

        private Rfc6979() {}

        @NonNull
        static BigInteger generate(@NonNull final byte[] privKeyBytes, @NonNull final byte[] hash) {
            byte[] v = new byte[32];
            java.util.Arrays.fill(v, (byte) 0x01);
            byte[] k = new byte[32];

            k = hmacSha256(k, concat(v, new byte[] {0x00}, privKeyBytes, hash));
            v = hmacSha256(k, v);
            k = hmacSha256(k, concat(v, new byte[] {0x01}, privKeyBytes, hash));
            v = hmacSha256(k, v);

            for (int attempt = 0; attempt < 1000; attempt++) {
                v = hmacSha256(k, v);
                final BigInteger candidate = new BigInteger(1, v);
                if (candidate.signum() > 0 && candidate.compareTo(N) < 0) {
                    return candidate;
                }
                k = hmacSha256(k, concat(v, new byte[] {0x00}));
                v = hmacSha256(k, v);
            }
            throw new IllegalStateException("RFC 6979 failed after 1000 attempts");
        }

        @NonNull
        private static byte[] hmacSha256(@NonNull final byte[] key, @NonNull final byte[] data) {
            try {
                final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(data);
            } catch (final java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
                throw new IllegalStateException("HmacSHA256 not available", e);
            }
        }

        @NonNull
        private static byte[] concat(@NonNull final byte[]... parts) {
            int total = 0;
            for (final byte[] p : parts) {
                total += p.length;
            }
            final byte[] out = new byte[total];
            int pos = 0;
            for (final byte[] p : parts) {
                System.arraycopy(p, 0, out, pos, p.length);
                pos += p.length;
            }
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // Internal: pure-Java Keccak-256 (Ethereum flavour, padding byte 0x01)
    // -------------------------------------------------------------------------

    private static final class Keccak256 {

        private static final int RATE_BYTES = 136; // (1600 - 2*256) / 8
        private static final int ROUNDS = 24;

        // Round constants
        private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
        };

        // Rotation offsets
        private static final int[] R = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14
        };

        private Keccak256() {}

        @NonNull
        static byte[] keccak256(@NonNull final byte[] input) {
            final long[] state = new long[25];

            final int fullBlocks = input.length / RATE_BYTES;
            for (int b = 0; b < fullBlocks; b++) {
                absorbBlock(state, input, b * RATE_BYTES, RATE_BYTES);
                keccakF(state);
            }

            final int remaining = input.length - fullBlocks * RATE_BYTES;
            final byte[] lastBlock = new byte[RATE_BYTES];
            System.arraycopy(input, fullBlocks * RATE_BYTES, lastBlock, 0, remaining);
            lastBlock[remaining] = 0x01;
            lastBlock[RATE_BYTES - 1] |= (byte) 0x80;
            absorbBlock(state, lastBlock, 0, RATE_BYTES);
            keccakF(state);

            final byte[] out = new byte[32];
            for (int i = 0; i < 32; i++) {
                final long lane = state[i / 8];
                out[i] = (byte) ((lane >>> (8 * (i % 8))) & 0xff);
            }
            return out;
        }

        private static void absorbBlock(final long[] state, final byte[] data, final int offset, final int length) {
            for (int i = 0; i < length / 8; i++) {
                long lane = 0L;
                for (int j = 0; j < 8; j++) {
                    lane |= (data[offset + i * 8 + j] & 0xffL) << (8 * j);
                }
                state[i] ^= lane;
            }
        }

        private static void keccakF(final long[] s) {
            final long[] C = new long[5];
            final long[] D = new long[5];
            final long[] B = new long[25];
            for (int round = 0; round < ROUNDS; round++) {
                for (int x = 0; x < 5; x++) {
                    C[x] = s[x] ^ s[x + 5] ^ s[x + 10] ^ s[x + 15] ^ s[x + 20];
                }
                for (int x = 0; x < 5; x++) {
                    D[x] = C[(x + 4) % 5] ^ Long.rotateLeft(C[(x + 1) % 5], 1);
                }
                for (int i = 0; i < 25; i++) {
                    s[i] ^= D[i % 5];
                }
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 5; y++) {
                        final int idx = x + 5 * y;
                        final int newIdx = y + 5 * ((2 * x + 3 * y) % 5);
                        B[newIdx] = Long.rotateLeft(s[idx], R[idx]);
                    }
                }
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        s[x + 5 * y] = B[x + 5 * y] ^ ((~B[((x + 1) % 5) + 5 * y]) & B[((x + 2) % 5) + 5 * y]);
                    }
                }
                s[0] ^= RC[round];
            }
        }
    }
}
