// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Client-side Ethereum transaction signing using secp256k1 ECDSA.
 *
 * <p>This class provides a pure-Java implementation of secp256k1 elliptic-curve arithmetic
 * sufficient to sign Ethereum transactions without any external cryptographic library. It
 * supports:
 * <ul>
 *   <li>Deriving the public key and Ethereum address from a private key hex string</li>
 *   <li>Signing a transaction digest with ECDSA using RFC 6979 deterministic nonce generation</li>
 *   <li>Encoding a signed EIP-1559 (type-2) transaction as the {@code 0x02}-prefixed envelope</li>
 *   <li>Signing arbitrary messages with the Ethereum personal-sign (EIP-191) prefix</li>
 * </ul>
 *
 * <p><b>Security note:</b> This implementation uses RFC 6979 deterministic {@code k} generation
 * so the signing nonce is derived deterministically from the key and message hash.
 */
public final class EthSigner {

    // -------------------------------------------------------------------------
    // secp256k1 curve parameters (SECG SEC 2, §2.4.1)
    // -------------------------------------------------------------------------

    /** Field prime p for secp256k1. */
    private static final BigInteger P =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);

    /** Curve order n. */
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    /** Generator x-coordinate. */
    private static final BigInteger GX =
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);

    /** Generator y-coordinate. */
    private static final BigInteger GY =
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);

    private static final BigInteger TWO = BigInteger.TWO;
    private static final BigInteger THREE = BigInteger.valueOf(3L);

    private final BigInteger privateKey;
    private final String address;

    /**
     * Create an {@code EthSigner} from a hex-encoded private key.
     *
     * @param privateKeyHex the 32-byte private key as a hex string (with or without {@code 0x})
     * @throws IllegalArgumentException if the key is invalid
     */
    public EthSigner(@NonNull final String privateKeyHex) {
        final String stripped = privateKeyHex.startsWith("0x") || privateKeyHex.startsWith("0X")
                ? privateKeyHex.substring(2)
                : privateKeyHex;
        this.privateKey = new BigInteger(stripped, 16);
        if (this.privateKey.signum() <= 0 || this.privateKey.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Private key is out of range for secp256k1");
        }
        this.address = deriveAddress(this.privateKey);
    }

    /**
     * Return the Ethereum address (20 bytes, lowercase {@code 0x}-prefixed hex) derived from
     * this signer's private key.
     *
     * @return the Ethereum address
     */
    @NonNull
    public String address() {
        return address;
    }

    /**
     * Sign an EIP-1559 (type-2) transaction and return the fully encoded signed bytes.
     *
     * <p>The output is the envelope {@code 0x02 || rlp([chainId, nonce, maxPriorityFeePerGas,
     * maxFeePerGas, gasLimit, to, value, data, accessList=[], yParity, r, s])} as defined in
     * EIP-1559. The signing digest is {@code keccak256(0x02 || rlp([...without sig fields]))}.
     * The access list is always emitted as an empty RLP list.
     *
     * @param nonce                  the sender nonce
     * @param maxPriorityFeePerGasWei the EIP-1559 priority fee (miner tip) in wei
     * @param maxFeePerGasWei        the EIP-1559 max fee per gas (base + priority cap) in wei
     * @param gasLimit               gas limit
     * @param to                     recipient address (hex with {@code 0x} prefix), or
     *                               {@code null} for a contract-creation transaction
     * @param valueWei               value in wei (typically 0 for contract calls)
     * @param data                   call data bytes (or constructor bytecode + args for deployment)
     * @param chainId                EIP-155 chain ID
     * @return the type-2 envelope: {@code 0x02 || rlp([...])}
     */
    @NonNull
    public byte[] signEip1559Transaction(
            final long nonce,
            final long maxPriorityFeePerGasWei,
            final long maxFeePerGasWei,
            final long gasLimit,
            @Nullable final String to,
            final long valueWei,
            @NonNull final byte[] data,
            final long chainId) {
        // Step 1: build the unsigned RLP payload (without sig fields) and prepend the type byte
        final byte[] unsignedPayload = rlpEncodeEip1559ForSigning(
                chainId, nonce, maxPriorityFeePerGasWei, maxFeePerGasWei, gasLimit, to, valueWei, data);
        final byte[] signingInput = new byte[1 + unsignedPayload.length];
        signingInput[0] = (byte) 0x02;
        System.arraycopy(unsignedPayload, 0, signingInput, 1, unsignedPayload.length);

        // Step 2: hash and sign
        final byte[] hash = AbiCodec.Keccak256.keccak256(signingInput);
        final BigInteger[] sig = ecdsaSign(hash);
        final BigInteger bigR = sig[0];
        final BigInteger bigS = sig[1];
        // yParity is the single low bit of the recoveryId; the bit-1 case (Rx >= N) has
        // probability ~2^-128 with RFC 6979 and is not representable in EIP-1559.
        final long yParity = sig[2].longValue() & 1L;

        // Step 3: build the signed envelope
        final byte[] signedPayload = rlpEncodeEip1559Signed(
                chainId,
                nonce,
                maxPriorityFeePerGasWei,
                maxFeePerGasWei,
                gasLimit,
                to,
                valueWei,
                data,
                yParity,
                bigR,
                bigS);
        final byte[] out = new byte[1 + signedPayload.length];
        out[0] = (byte) 0x02;
        System.arraycopy(signedPayload, 0, out, 1, signedPayload.length);
        return out;
    }

    // -------------------------------------------------------------------------
    // ECDSA signing
    // -------------------------------------------------------------------------

    /**
     * Sign a 32-byte message hash using ECDSA with RFC 6979 deterministic k.
     *
     * @param hash the 32-byte message hash
     * @return [r, s, recoveryId] as BigIntegers
     */
    @NonNull
    BigInteger[] ecdsaSign(@NonNull final byte[] hash) {
        if (hash.length != 32) {
            throw new IllegalArgumentException("Hash must be 32 bytes, got " + hash.length);
        }
        final byte[] privKeyBytes = bigIntTo32Bytes(privateKey);
        final BigInteger z = new BigInteger(1, hash);

        // RFC 6979: generate deterministic k
        final BigInteger k = rfc6979(privKeyBytes, hash);
        final BigInteger[] kPoint = pointMultiply(GX, GY, k);
        final BigInteger Rx = kPoint[0];
        final BigInteger r = Rx.mod(N);
        if (r.signum() == 0) {
            throw new IllegalStateException("RFC 6979 produced r=0; key or hash is degenerate");
        }
        // s = k^-1 * (z + r * privKey) mod n
        BigInteger s = k.modInverse(N).multiply(z.add(r.multiply(privateKey))).mod(N);

        // Recovery ID is derived directly from R:
        //   bit 0 = parity of R.y
        //   bit 1 = whether R.x had to be reduced mod n (Rx >= N)
        int recId = (kPoint[1].testBit(0) ? 1 : 0) | (Rx.compareTo(N) >= 0 ? 2 : 0);

        // Normalise s to low-S (BIP-62): negating s flips R.y, so flip recId bit 0
        if (s.compareTo(N.shiftRight(1)) > 0) {
            s = N.subtract(s);
            recId ^= 1;
        }

        return new BigInteger[] {r, s, BigInteger.valueOf(recId)};
    }

    // -------------------------------------------------------------------------
    // RFC 6979 deterministic k
    // -------------------------------------------------------------------------

    /**
     * Generate a deterministic {@code k} per RFC 6979 §3.2 using HMAC-SHA256.
     *
     * @param privKeyBytes the 32-byte private key
     * @param hash         the 32-byte message hash
     * @return a deterministic, non-zero {@code k} in [1, n-1]
     */
    @NonNull
    private static BigInteger rfc6979(@NonNull final byte[] privKeyBytes, @NonNull final byte[] hash) {
        byte[] v = new byte[32];
        Arrays.fill(v, (byte) 0x01);
        byte[] k = new byte[32]; // all zeros

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
        throw new IllegalStateException("RFC 6979 failed to produce a valid k after 1000 attempts");
    }

    @NonNull
    private static byte[] hmacSha256(@NonNull final byte[] key, @NonNull final byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    // -------------------------------------------------------------------------
    // secp256k1 elliptic curve arithmetic (affine coordinates)
    // -------------------------------------------------------------------------

    /**
     * Point addition on secp256k1 in affine coordinates.
     * Assumes both points are on the curve and neither is the point at infinity.
     */
    @NonNull
    private static BigInteger[] pointAdd(
            @NonNull final BigInteger x1,
            @NonNull final BigInteger y1,
            @NonNull final BigInteger x2,
            @NonNull final BigInteger y2) {
        if (x1.equals(x2) && y1.equals(y2)) {
            return pointDouble(x1, y1);
        }
        if (x1.equals(x2)) {
            // Points are inverses; the sum is the point at infinity, which has no affine
            // representation. With RFC 6979 deterministic k and signing-only usage, this case
            // is unreachable in practice; fail loudly if it ever occurs.
            throw new IllegalStateException("pointAdd: result is the point at infinity");
        }
        final BigInteger dx = x2.subtract(x1).mod(P);
        final BigInteger dy = y2.subtract(y1).mod(P);
        final BigInteger slope = dy.multiply(dx.modInverse(P)).mod(P);
        final BigInteger x3 = slope.multiply(slope).subtract(x1).subtract(x2).mod(P);
        final BigInteger y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(P);
        return new BigInteger[] {x3, y3};
    }

    @NonNull
    private static BigInteger[] pointDouble(@NonNull final BigInteger x, @NonNull final BigInteger y) {
        final BigInteger slope = THREE.multiply(x.multiply(x))
                .multiply(TWO.multiply(y).modInverse(P))
                .mod(P);
        final BigInteger x3 = slope.multiply(slope).subtract(TWO.multiply(x)).mod(P);
        final BigInteger y3 = slope.multiply(x.subtract(x3)).subtract(y).mod(P);
        return new BigInteger[] {x3, y3};
    }

    /**
     * Scalar multiplication on secp256k1: returns {@code scalar * (gx, gy)} using double-and-add.
     */
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
            throw new IllegalArgumentException("Scalar multiplication by zero produced point at infinity");
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // RLP encoding for Ethereum transactions
    // -------------------------------------------------------------------------

    /**
     * RLP-encode the signing payload for an EIP-1559 (type-2) transaction:
     * {@code rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gas, to, value, data, []])}.
     */
    @NonNull
    private static byte[] rlpEncodeEip1559ForSigning(
            final long chainId,
            final long nonce,
            final long maxPriorityFeePerGasWei,
            final long maxFeePerGasWei,
            final long gasLimit,
            @Nullable final String to,
            final long valueWei,
            @NonNull final byte[] data) {
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(rlpEncodeUnsignedLong(chainId));
        payload.writeBytes(rlpEncodeUnsignedLong(nonce));
        payload.writeBytes(rlpEncodeUnsignedLong(maxPriorityFeePerGasWei));
        payload.writeBytes(rlpEncodeUnsignedLong(maxFeePerGasWei));
        payload.writeBytes(rlpEncodeUnsignedLong(gasLimit));
        payload.writeBytes(rlpEncodeOptionalAddress(to));
        payload.writeBytes(rlpEncodeUnsignedLong(valueWei));
        payload.writeBytes(rlpEncodeBytes(data));
        payload.writeBytes(rlpEncodeEmptyList());
        return rlpEncodeList(payload.toByteArray());
    }

    /**
     * RLP-encode a fully signed EIP-1559 (type-2) transaction payload:
     * {@code rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gas, to, value, data, [],
     * yParity, r, s])}. The caller prepends the {@code 0x02} type byte.
     */
    @NonNull
    private static byte[] rlpEncodeEip1559Signed(
            final long chainId,
            final long nonce,
            final long maxPriorityFeePerGasWei,
            final long maxFeePerGasWei,
            final long gasLimit,
            @Nullable final String to,
            final long valueWei,
            @NonNull final byte[] data,
            final long yParity,
            @NonNull final BigInteger r,
            @NonNull final BigInteger s) {
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(rlpEncodeUnsignedLong(chainId));
        payload.writeBytes(rlpEncodeUnsignedLong(nonce));
        payload.writeBytes(rlpEncodeUnsignedLong(maxPriorityFeePerGasWei));
        payload.writeBytes(rlpEncodeUnsignedLong(maxFeePerGasWei));
        payload.writeBytes(rlpEncodeUnsignedLong(gasLimit));
        payload.writeBytes(rlpEncodeOptionalAddress(to));
        payload.writeBytes(rlpEncodeUnsignedLong(valueWei));
        payload.writeBytes(rlpEncodeBytes(data));
        payload.writeBytes(rlpEncodeEmptyList());
        payload.writeBytes(rlpEncodeUnsignedLong(yParity));
        payload.writeBytes(rlpEncodeBigIntUnsigned(r));
        payload.writeBytes(rlpEncodeBigIntUnsigned(s));
        return rlpEncodeList(payload.toByteArray());
    }

    /**
     * RLP-encode a 20-byte address, or the empty byte string ({@code 0x80}) when {@code to} is
     * {@code null} (contract-creation transaction).
     */
    @NonNull
    private static byte[] rlpEncodeOptionalAddress(@Nullable final String hexAddress) {
        if (hexAddress == null) {
            return new byte[] {(byte) 0x80};
        }
        return rlpEncodeAddress(hexAddress);
    }

    /**
     * RLP-encode an empty list as the single byte {@code 0xc0}.
     */
    @NonNull
    static byte[] rlpEncodeEmptyList() {
        return new byte[] {(byte) 0xc0};
    }

    /**
     * RLP-encode a non-negative long as a minimal big-endian integer.
     * Zero is encoded as the empty byte string ({@code 0x80}).
     */
    @NonNull
    static byte[] rlpEncodeUnsignedLong(final long value) {
        if (value == 0L) {
            return new byte[] {(byte) 0x80};
        }
        // Pack into 8 bytes big-endian, then strip leading zeros
        final byte[] raw = new byte[8];
        for (int i = 0; i < 8; i++) {
            raw[7 - i] = (byte) ((value >>> (i * 8)) & 0xff);
        }
        int start = 0;
        while (start < 8 && raw[start] == 0) {
            start++;
        }
        final byte[] trimmed = new byte[8 - start];
        System.arraycopy(raw, start, trimmed, 0, trimmed.length);
        return rlpEncodeByteStringRaw(trimmed);
    }

    /**
     * RLP-encode a non-negative {@link BigInteger} as a minimal big-endian unsigned integer.
     */
    @NonNull
    private static byte[] rlpEncodeBigIntUnsigned(@NonNull final BigInteger value) {
        if (value.signum() == 0) {
            return new byte[] {(byte) 0x80};
        }
        byte[] raw = value.toByteArray();
        // BigInteger.toByteArray() may prepend a leading zero sign byte; strip it
        if (raw[0] == 0 && raw.length > 1) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        return rlpEncodeByteStringRaw(raw);
    }

    /**
     * RLP-encode a 20-byte Ethereum address (hex string with optional {@code 0x} prefix).
     */
    @NonNull
    private static byte[] rlpEncodeAddress(@NonNull final String hexAddress) {
        final byte[] addr = AbiCodec.fromHex(hexAddress);
        if (addr.length != 20) {
            throw new IllegalArgumentException("Address must be 20 bytes, got " + addr.length);
        }
        return rlpEncodeByteStringRaw(addr);
    }

    /**
     * RLP-encode a variable-length byte array.
     */
    @NonNull
    private static byte[] rlpEncodeBytes(@NonNull final byte[] value) {
        if (value.length == 0) {
            return new byte[] {(byte) 0x80};
        }
        return rlpEncodeByteStringRaw(value);
    }

    /**
     * Emit the RLP byte-string encoding: header byte(s) followed by the raw content.
     * Handles the single-byte shortcut for values in [0x00, 0x7f].
     */
    @NonNull
    private static byte[] rlpEncodeByteStringRaw(@NonNull final byte[] raw) {
        if (raw.length == 1 && (raw[0] & 0xff) < 0x80) {
            return raw.clone();
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRlpStringHeader(out, raw.length);
        out.writeBytes(raw);
        return out.toByteArray();
    }

    @NonNull
    private static byte[] rlpEncodeList(@NonNull final byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRlpListHeader(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void writeRlpStringHeader(@NonNull final ByteArrayOutputStream out, final int length) {
        if (length < 56) {
            out.write(0x80 + length);
        } else {
            final byte[] lenBytes = minimalBigEndian(length);
            out.write(0xb7 + lenBytes.length);
            out.writeBytes(lenBytes);
        }
    }

    private static void writeRlpListHeader(@NonNull final ByteArrayOutputStream out, final int length) {
        if (length < 56) {
            out.write(0xc0 + length);
        } else {
            final byte[] lenBytes = minimalBigEndian(length);
            out.write(0xf7 + lenBytes.length);
            out.writeBytes(lenBytes);
        }
    }

    @NonNull
    private static byte[] minimalBigEndian(final int value) {
        if (value == 0) {
            return new byte[0];
        }
        final int byteCount = (32 - Integer.numberOfLeadingZeros(value) + 7) / 8;
        final byte[] out = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            out[byteCount - 1 - i] = (byte) ((value >>> (8 * i)) & 0xff);
        }
        return out;
    }

    /**
     * Sign a message for on-chain endpoint signature verification.
     *
     * <p>Applies the Ethereum personal-sign prefix before signing, matching the
     * on-chain check in {@code MessagingLogic._checkPeerEndpointSignature}:
     * <pre>
     *   ethSignedHash = keccak256("\x19Ethereum Signed Message:\n32" || msgHash)
     *   ecrecover(ethSignedHash, r, s, v)
     * </pre>
     *
     * @param msgHash pre-computed 32-byte digest to sign (before prefix)
     * @return 65-byte {@code r(32)||s(32)||v(1)} signature (v = 27 or 28)
     */
    public byte[] signMessage(@NonNull final byte[] msgHash) {
        if (msgHash.length != 32) {
            throw new IllegalArgumentException("msgHash must be 32 bytes, got " + msgHash.length);
        }
        final byte[] prefix = "\u0019Ethereum Signed Message:\n32".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] prefixed = concat(prefix, msgHash);
        final byte[] ethSignedHash = AbiCodec.Keccak256.keccak256(prefixed);

        final BigInteger[] sig = ecdsaSign(ethSignedHash);
        final BigInteger r = sig[0];
        final BigInteger s = sig[1];
        final long recId = sig[2].longValue();
        final long v = recId + 27L;

        final byte[] out = new byte[65];
        System.arraycopy(bigIntTo32Bytes(r), 0, out, 0, 32);
        System.arraycopy(bigIntTo32Bytes(s), 0, out, 32, 32);
        out[64] = (byte) v;
        return out;
    }

    // -------------------------------------------------------------------------
    // Address derivation helpers
    // -------------------------------------------------------------------------

    /**
     * Derive the 64-byte uncompressed secp256k1 public key (X||Y) from a private key.
     *
     * @param privateKeyHex the 32-byte private key as a hex string (with or without {@code 0x})
     * @return the 64-byte uncompressed public key (X||Y, no 0x04 prefix)
     */
    @NonNull
    public static byte[] derivePublicKey(@NonNull final String privateKeyHex) {
        final String stripped = privateKeyHex.startsWith("0x") || privateKeyHex.startsWith("0X")
                ? privateKeyHex.substring(2)
                : privateKeyHex;
        final BigInteger privKey = new BigInteger(stripped, 16);
        final BigInteger[] pub = pointMultiply(GX, GY, privKey);
        return concat(bigIntTo32Bytes(pub[0]), bigIntTo32Bytes(pub[1]));
    }

    @NonNull
    private static String deriveAddress(@NonNull final BigInteger privKey) {
        final BigInteger[] pub = pointMultiply(GX, GY, privKey);
        // Uncompressed public key: 0x04 || x(32) || y(32) — hash only the 64 key bytes
        final byte[] xBytes = bigIntTo32Bytes(pub[0]);
        final byte[] yBytes = bigIntTo32Bytes(pub[1]);
        final byte[] pubKeyBytes = concat(xBytes, yBytes);
        final byte[] hash = AbiCodec.Keccak256.keccak256(pubKeyBytes);
        // Ethereum address = last 20 bytes of keccak256(pubKey)
        final StringBuilder sb = new StringBuilder(42).append("0x");
        for (int i = 12; i < 32; i++) {
            final int b = hash[i] & 0xff;
            sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    @NonNull
    static byte[] bigIntTo32Bytes(@NonNull final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            // Skip leading sign byte
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
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
