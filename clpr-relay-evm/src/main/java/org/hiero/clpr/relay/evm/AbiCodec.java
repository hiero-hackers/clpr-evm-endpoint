// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;

/**
 * Minimal Solidity ABI encoder/decoder for the handful of types used by the CLPR Relay.
 *
 * <p>This class intentionally avoids bringing in Web3j or any other dependency. It supports
 * only the types needed by the relay: {@code bytes32}, {@code bytes}, {@code string},
 * {@code uint8/32/64/256}, and {@code address}.
 *
 * <p>Function selectors are computed as the first four bytes of
 * {@code keccak256("name(types,...)")}. A small pure-Java Keccak-256 implementation is
 * provided in the nested {@link Keccak256} class.
 */
public final class AbiCodec {

    private AbiCodec() {}

    // -------------------------------------------------------------------------
    // Function selector / call encoding
    // -------------------------------------------------------------------------

    /**
     * Compute the 4-byte function selector for a Solidity function signature.
     *
     * @param signature the function signature (e.g. {@code "submitBundle(bytes32,bytes)"})
     * @return a 4-byte selector
     */
    public static byte[] functionSelector(final String signature) {
        final byte[] hash = Keccak256.keccak256(signature.getBytes(StandardCharsets.UTF_8));
        final byte[] sel = new byte[4];
        System.arraycopy(hash, 0, sel, 0, 4);
        return sel;
    }

    /**
     * Encode a function call where every parameter is already encoded as a 32-byte "head" slot.
     * Dynamic types must be handled by the caller (use {@link #encodeSubmitBundle} or
     * {@link #encodeGetMessage} for helpers tailored to this project's needs).
     *
     * @param signature  the function signature
     * @param headParams each must be 32 bytes
     * @return selector || concatenated 32-byte head slots
     */
    public static byte[] encodeFunctionCall(final String signature, final byte[]... headParams) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(functionSelector(signature));
        for (final byte[] p : headParams) {
            if (p.length != 32) {
                throw new IllegalArgumentException("head param must be 32 bytes, got " + p.length);
            }
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /**
     * ABI-encode a call to {@code submitBundle(bytes32,bytes)}.
     *
     * @param channelId32 the 32-byte channel id
     * @param proofBytes     dynamic-length proof bytes
     * @return the full call data (selector + encoded args)
     */
    public static byte[] encodeSubmitBundle(final byte[] channelId32, final byte[] proofBytes) {
        if (channelId32.length != 32) {
            throw new IllegalArgumentException("channelId must be 32 bytes, got " + channelId32.length);
        }
        final byte[] selector = functionSelector("submitBundle(bytes32,bytes)");
        // Head (2 slots): [bytes32 channelId][offset to proofBytes]
        // Offset = 64 (2 head slots × 32 bytes each)
        final byte[] offsetSlot = new byte[32];
        offsetSlot[31] = 0x40; // offset = 64
        final byte[] lenSlot = encodeUint(proofBytes.length);
        final byte[] paddedProof = padRight32(proofBytes);

        final byte[] encoded = new byte[selector.length + 32 + 32 + 32 + paddedProof.length];
        int pos = 0;
        System.arraycopy(selector, 0, encoded, pos, 4);
        pos += 4;
        System.arraycopy(channelId32, 0, encoded, pos, 32);
        pos += 32;
        System.arraycopy(offsetSlot, 0, encoded, pos, 32);
        pos += 32;
        System.arraycopy(lenSlot, 0, encoded, pos, 32);
        pos += 32;
        System.arraycopy(paddedProof, 0, encoded, pos, paddedProof.length);
        return encoded;
    }

    /**
     * ABI-encode a call to {@code getMessage(bytes32,uint64)}.
     */
    public static byte[] encodeGetMessage(final byte[] channelId32, final long messageId) {
        if (channelId32.length != 32) {
            throw new IllegalArgumentException("channelId must be 32 bytes");
        }
        return encodeFunctionCall("getMessage(bytes32,uint64)", channelId32, encodeUint(messageId));
    }

    /**
     * ABI-encode a call to {@code getChannel(bytes32)}.
     */
    public static byte[] encodeGetChannel(final byte[] channelId32) {
        if (channelId32.length != 32) {
            throw new IllegalArgumentException("channelId must be 32 bytes");
        }
        return encodeFunctionCall("getChannel(bytes32)", channelId32);
    }

    /**
     * ABI-encode a call to {@code getLedgerConfiguration()} (no args).
     */
    public static byte[] encodeGetLedgerConfiguration() {
        return functionSelector("getLedgerConfiguration()");
    }

    /**
     * ABI-encode a call to {@code channelCount()} (no args). Returns the number of completed
     * channels registered on the {@code ClprService} contract — used by the discovery poller as
     * a cheap change signal.
     */
    public static byte[] encodeChannelCount() {
        return functionSelector("channelCount()");
    }

    /**
     * ABI-encode a call to {@code getEndpointManifest()} (no args).
     */
    public static byte[] encodeGetEndpointManifest() {
        return functionSelector("getEndpointManifest()");
    }

    /**
     * ABI-encode a call to {@code getPeerEndpointManifest(bytes32)}.
     */
    public static byte[] encodeGetPeerEndpointManifest(final byte[] channelId32) {
        if (channelId32.length != 32) {
            throw new IllegalArgumentException("channelId must be 32 bytes");
        }
        return encodeFunctionCall("getPeerEndpointManifest(bytes32)", channelId32);
    }

    // -------------------------------------------------------------------------
    // Primitive encoders
    // -------------------------------------------------------------------------

    /**
     * Encode a 32-byte value. The input must be exactly 32 bytes.
     */
    public static byte[] encodeBytes32(final byte[] value) {
        if (value.length != 32) {
            throw new IllegalArgumentException("bytes32 must be 32 bytes, got " + value.length);
        }
        return value.clone();
    }

    /**
     * Encode an unsigned integer as a 32-byte big-endian value.
     * Accepts any non-negative {@code long}.
     */
    public static byte[] encodeUint(final long value) {
        if (value < 0) {
            // FIXME: This is obviously wrong. the full bit range should be supported for a uint, which for
            //        java *would* be a negative number. The same thing could be said for every single place
            //        in this file where we're throwing. The argument in favor of this throw is to say
            //        "the developer sent a negative number *thinking* it was a positive number, so there
            //        is some kind of bug, and if they wanted to send a 64-bit unsigned int they need to use
            //        BigInteger or something instead". But that's pretty lame. I really, really wish Java
            //        had support for unsigned types. Sigh. Valhalla come.
            throw new IllegalArgumentException("encodeUint does not support negative values: " + value);
        }
        final byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[31 - i] = (byte) ((value >>> (8 * i)) & 0xff);
        }
        return out;
    }

    /**
     * Encode an unsigned 256-bit integer as a 32-byte big-endian word. Accepts any
     * non-negative {@link BigInteger} that fits in 256 bits.
     *
     * @param value the non-negative value to encode
     * @return a 32-byte big-endian representation, left-padded with zeros
     */
    public static byte[] encodeUint256(final BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("encodeUint256 does not support negative values: " + value);
        }
        if (value.bitLength() > 256) {
            throw new IllegalArgumentException("value exceeds 256 bits: " + value);
        }
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            // BigInteger sign byte may prepend a leading zero; skip it.
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }

    /**
     * Concatenate the given byte arrays into a single array.
     *
     * @param parts the pieces to concatenate in order
     * @return the concatenation
     */
    public static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /**
     * Encode a dynamic {@code bytes} value standalone: length (32 bytes) followed by the
     * padded data. Typical callers use {@link #encodeSubmitBundle} instead.
     */
    public static byte[] encodeBytes(final byte[] value) {
        final byte[] padded = padRight32(value);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(32 + padded.length);
        out.writeBytes(encodeUint(value.length));
        out.writeBytes(padded);
        return out.toByteArray();
    }

    /**
     * Right-pad a byte array with zeros so its length is a multiple of 32.
     *
     * @param value the value to pad
     * @return a copy of {@code value} whose length is rounded up to the next multiple of 32
     */
    public static byte[] padRight32(final byte[] value) {
        final int rem = value.length % 32;
        if (rem == 0) {
            return value.clone();
        }
        final byte[] out = new byte[value.length + (32 - rem)];
        System.arraycopy(value, 0, out, 0, value.length);
        return out;
    }

    // -------------------------------------------------------------------------
    // Primitive decoders
    // -------------------------------------------------------------------------

    /**
     * Decode a {@code uint64} from the low 8 bytes of a 32-byte big-endian word at
     * {@code data[offset..offset+32]}.
     *
     * <p>This method does not validate that the upper 24 bytes are zero — it simply returns the
     * low 64 bits of the ABI word as a Java {@code long}. Values whose high bit is set will be
     * returned as negative {@code long} values. Not suitable For ABI offsets and lengths (which are encoded as
     * {@code uint256}).
     */
    public static long decodeUint64(final byte[] data, final int offset) {
        checkRange(data, offset, 32);
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[offset + 24 + i] & 0xffL);
        }
        return v;
    }

    /**
     * Decode a {@code uint96} from the low 12 bytes of a 32-byte big-endian word at
     * {@code data[offset..offset+32]}.
     *
     * <p>Returned as an unsigned (non-negative) {@link BigInteger} — a {@code uint96} does not fit
     * in a Java {@code long}. The upper 20 bytes of the word are not validated to be zero.
     *
     * <p>This method does not validate that the upper 20 bytes are zero — it simply returns the
     * low 96 bits of the ABI word as a Java {@code long}. Values whose high bit is set will be
     * returned as negative {@code long} values. Not suitable For ABI offsets and lengths (which are encoded as
     * {@code uint256}).
     */
    public static BigInteger decodeUint96(final byte[] data, final int offset) {
        checkRange(data, offset, 32);
        // signum=1 + 12-byte big-endian magnitude → always non-negative.
        return new BigInteger(1, Arrays.copyOfRange(data, offset + 20, offset + 32));
    }

    /**
     * Copy the 32-byte word at {@code data[offset..offset+32]}.
     */
    public static byte[] decodeBytes32(final byte[] data, final int offset) {
        checkRange(data, offset, 32);
        final byte[] out = new byte[32];
        System.arraycopy(data, offset, out, 0, 32);
        return out;
    }

    /**
     * Decode an ABI head word (a {@code uint256} offset or length) at {@code data[offset..offset+32]}
     * as a non-negative {@code int}, rejecting the malformed/adversarial cases that a plain
     * {@code (int) decodeUint64(...)} would silently accept.
     *
     * <p>{@link #decodeUint64} keeps only the low 64 bits and never inspects the upper 192, so a
     * {@code uint256} whose real value is huge can wrap to a small — even negative — {@code long},
     * and the subsequent {@code (int)} narrowing can wrap again. Either wrap produces an index that
     * passes a naive {@code pos + len <= result.length} bounds check yet points nowhere valid. Offsets
     * and lengths are used to index into the ABI return buffer, so any value that does not fit a
     * non-negative {@code int} (i.e. exceeds {@link Integer#MAX_VALUE}, which no legitimate offset into
     * a return buffer ever does) is a decode fault and must be surfaced, not tolerated.
     *
     * @param data   the ABI-encoded buffer
     * @param offset byte offset of the 32-byte word to decode
     * @return the word value as a non-negative {@code int} in {@code [0, Integer.MAX_VALUE]}
     * @throws IndexOutOfBoundsException if the word does not lie within {@code data}
     * @throws IllegalArgumentException  if the word's value does not fit a non-negative {@code int}
     */
    public static int decodeOffsetOrLength(final byte[] data, final int offset) {
        checkRange(data, offset, 32);
        // Interpret all 32 bytes as an unsigned magnitude; bitLength() > 31 means the value is
        // >= 2^31 and therefore exceeds Integer.MAX_VALUE (this also catches any of the upper 224
        // bits being set — the case decodeUint64 would drop).
        final BigInteger word = new BigInteger(1, Arrays.copyOfRange(data, offset, offset + 32));
        if (word.bitLength() > 31) {
            throw new IllegalArgumentException(
                    "ABI offset/length at byte " + offset + " does not fit a non-negative int: 0x" + word.toString(16));
        }
        return word.intValueExact();
    }

    /**
     * Follow a dynamic-bytes offset stored in {@code data[staticOffset..staticOffset+32]}.
     * The offset is measured from the start of {@code data}. Returns the raw
     * (un-padded) bytes.
     */
    @NonNull
    public static byte[] decodeDynamicBytes(@NonNull final byte[] data, final int staticOffset) {
        final int dataOffset = Math.toIntExact(decodeUint64(data, staticOffset));
        final int length = Math.toIntExact(decodeUint64(data, dataOffset));
        checkRange(data, dataOffset + 32, length);
        final byte[] out = new byte[length];
        System.arraycopy(data, dataOffset + 32, out, 0, length);
        return out;
    }

    /**
     * Decode a dynamic UTF-8 {@code string} at the given static offset.
     */
    public static String decodeString(final byte[] data, final int staticOffset) {
        return new String(decodeDynamicBytes(data, staticOffset), StandardCharsets.UTF_8);
    }

    /**
     * Decode an {@code address} (right-aligned in a 32-byte word) as a lowercase
     * {@code 0x}-prefixed 40-hex-digit string.
     */
    public static String decodeAddress(final byte[] data, final int offset) {
        checkRange(data, offset, 32);
        final StringBuilder sb = new StringBuilder(42).append("0x");
        for (int i = 0; i < 20; i++) {
            final int b = data[offset + 12 + i] & 0xff;
            sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void checkRange(final byte[] data, final int offset, final int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    "range [" + offset + ", " + (offset + length) + ") out of bounds for length " + data.length);
        }
    }

    // -------------------------------------------------------------------------
    // Hex helpers
    // -------------------------------------------------------------------------

    /**
     * Encode a byte array as a lowercase {@code 0x}-prefixed hex string.
     */
    public static String toHex(final byte[] data) {
        final StringBuilder sb = new StringBuilder(2 + data.length * 2).append("0x");
        for (final byte b : data) {
            sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /**
     * Encode a byte array as a lowercase hex string WITHOUT the {@code 0x} prefix.
     * Useful for concatenating with pre-existing hex strings (e.g. contract bytecode).
     */
    public static String toHexNoPrefix(final byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (final byte b : data) {
            sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Address helpers
    // -------------------------------------------------------------------------

    /**
     * Encode a 20-byte Ethereum address (given as a hex string with optional {@code 0x} prefix)
     * as a 32-byte right-aligned ABI word (left-padded with zeros).
     *
     * @param hexAddress the address as a hex string (with or without {@code 0x} prefix)
     * @return a 32-byte ABI-encoded address word
     */
    public static byte[] encodeAddress(final String hexAddress) {
        final byte[] addr = fromHex(hexAddress);
        if (addr.length != 20) {
            throw new IllegalArgumentException("address must be 20 bytes, got " + addr.length);
        }
        final byte[] out = new byte[32];
        System.arraycopy(addr, 0, out, 12, 20);
        return out;
    }

    /**
     * Predict the address of a contract deployed from {@code senderHex} with the given
     * {@code nonce} using the standard Ethereum CREATE address formula:
     * {@code keccak256(rlp([sender, nonce]))[12:]}.
     *
     * @param senderHex the deploying EOA address (hex with or without {@code 0x} prefix)
     * @param nonce     the sender's nonce at the moment of deployment
     * @return the predicted contract address as a lowercase {@code 0x}-prefixed hex string
     */
    public static String predictContractAddress(final String senderHex, final long nonce) {
        if (nonce < 0) {
            throw new IllegalArgumentException("nonce must be non-negative: " + nonce);
        }
        final byte[] sender = fromHex(senderHex);
        if (sender.length != 20) {
            throw new IllegalArgumentException("sender must be 20 bytes, got " + sender.length);
        }
        final byte[] rlp = rlpEncodeSenderAndNonce(sender, nonce);
        final byte[] hash = Keccak256.keccak256(rlp);
        final StringBuilder sb = new StringBuilder(42).append("0x");
        for (int i = 0; i < 20; i++) {
            final int b = hash[12 + i] & 0xff;
            sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /**
     * Minimal RLP encoder for a two-element list [address(20 bytes), nonce(unsigned)].
     */
    private static byte[] rlpEncodeSenderAndNonce(final byte[] sender, final long nonce) {
        final byte[] encodedSender = rlpEncodeBytes(sender);
        final byte[] encodedNonce = rlpEncodeUint(nonce);
        final int payloadLen = encodedSender.length + encodedNonce.length;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRlpListHeader(out, payloadLen);
        out.writeBytes(encodedSender);
        out.writeBytes(encodedNonce);
        return out.toByteArray();
    }

    private static byte[] rlpEncodeBytes(final byte[] value) {
        // Single byte in [0x00, 0x7f] is its own encoding.
        if (value.length == 1 && (value[0] & 0xff) < 0x80) {
            return new byte[] {value[0]};
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeRlpStringHeader(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] rlpEncodeUint(final long value) {
        if (value == 0L) {
            // RLP encodes zero as empty string 0x80.
            return new byte[] {(byte) 0x80};
        }
        // Strip leading zero bytes.
        final byte[] raw = new byte[8];
        for (int i = 0; i < 8; i++) {
            raw[7 - i] = (byte) ((value >>> (8 * i)) & 0xff);
        }
        int start = 0;
        while (start < 8 && raw[start] == 0) {
            start++;
        }
        final byte[] trimmed = new byte[8 - start];
        System.arraycopy(raw, start, trimmed, 0, trimmed.length);
        return rlpEncodeBytes(trimmed);
    }

    private static void writeRlpStringHeader(final ByteArrayOutputStream out, final int length) {
        if (length < 56) {
            out.write(0x80 + length); // FIXME: Why not just and the bits? I guess it comes out the same
        } else {
            final byte[] lenBytes = minimalBigEndian(length);
            out.write(0xb7 + lenBytes.length);
            out.writeBytes(lenBytes);
        }
    }

    private static void writeRlpListHeader(final ByteArrayOutputStream out, final int length) {
        if (length < 56) {
            out.write(0xc0 + length);
        } else {
            final byte[] lenBytes = minimalBigEndian(length);
            out.write(0xf7 + lenBytes.length);
            out.writeBytes(lenBytes);
        }
    }

    private static byte[] minimalBigEndian(final int value) {
        if (value == 0) {
            return new byte[0];
        }
        final byte[] raw = new byte[] {
            (byte) ((value >>> 24) & 0xff),
            (byte) ((value >>> 16) & 0xff),
            (byte) ((value >>> 8) & 0xff),
            (byte) (value & 0xff)
        };
        int start = 0;
        while (start < 4 && raw[start] == 0) {
            start++;
        }
        final byte[] out = new byte[4 - start];
        System.arraycopy(raw, start, out, 0, out.length);
        return out;
    }

    public static byte[] rlpBigInt(BigInteger v) {
        if (v == null || v.signum() == 0) return new byte[] {(byte) 0x80};
        byte[] b = v.toByteArray();
        if (b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length); // strip sign byte
        return rlpBytes(b);
    }

    public static byte[] rlpBytes(byte[] b) {
        if (b.length == 1 && (b[0] & 0xFF) < 0x80) return b;
        return concat(rlpLen(b.length, 0x80), b);
    }

    public static byte[] rlpList(byte[]... items) {
        byte[] payload = new byte[0];
        for (byte[] item : items) payload = concat(payload, item);
        return concat(rlpLen(payload.length, 0xC0), payload);
    }

    public static byte[] rlpLen(int len, int offset) {
        if (len < 56) return new byte[] {(byte) (offset + len)};
        byte[] lb = BigInteger.valueOf(len).toByteArray();
        if (lb[0] == 0) lb = Arrays.copyOfRange(lb, 1, lb.length);
        return concat(new byte[] {(byte) (offset + 55 + lb.length)}, lb);
    }

    /**
     * Decode a hex string (with or without {@code 0x} prefix) into a byte array.
     */
    public static byte[] fromHex(final String hex) {
        final String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("hex string has odd length: " + hex);
        }
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(s.charAt(i * 2), 16);
            final int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex character in: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // =========================================================================
    // Keccak-256 (NIST FIPS 202's unofficial predecessor; used by Ethereum).
    // This is NOT SHA3-256: the padding byte is 0x01 instead of 0x06.
    // =========================================================================

    /**
     * Pure-Java Keccak-256 implementation following the Keccak sponge construction.
     *
     * <p>Uses a 1600-bit state (25 x 64-bit lanes), rate of 1088 bits (136 bytes) and
     * capacity of 512 bits. Domain separation byte is {@code 0x01} (Keccak, not SHA-3).
     */
    public static final class Keccak256 {

        private static final int RATE_BYTES = 136; // (1600 - 2*256) / 8
        private static final int ROUNDS = 24;

        private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
        };

        private static final int[] R = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14
        };

        private Keccak256() {}

        /**
         * Compute the Keccak-256 hash of {@code input}.
         *
         * @param input the message to hash (may be empty)
         * @return a 32-byte digest
         */
        public static byte[] keccak256(final byte[] input) {
            final long[] state = new long[25];

            // Absorb full blocks.
            final int fullBlocks = input.length / RATE_BYTES;
            for (int b = 0; b < fullBlocks; b++) {
                absorbBlock(state, input, b * RATE_BYTES, RATE_BYTES);
                keccakF(state);
            }

            // Last (possibly empty) block with padding.
            final int remaining = input.length - fullBlocks * RATE_BYTES;
            final byte[] lastBlock = new byte[RATE_BYTES];
            System.arraycopy(input, fullBlocks * RATE_BYTES, lastBlock, 0, remaining);
            // Keccak padding: 0x01 at the start of padding, 0x80 at the end.
            lastBlock[remaining] = 0x01;
            lastBlock[RATE_BYTES - 1] |= (byte) 0x80;
            absorbBlock(state, lastBlock, 0, RATE_BYTES);
            keccakF(state);

            // Squeeze 32 bytes.
            final byte[] out = new byte[32];
            for (int i = 0; i < 32; i++) {
                final long lane = state[i / 8];
                out[i] = (byte) ((lane >>> (8 * (i % 8))) & 0xff);
            }
            return out;
        }

        private static void absorbBlock(final long[] state, final byte[] data, final int offset, final int length) {
            // length must be a multiple of 8 and equal to RATE_BYTES.
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
                // Theta
                for (int x = 0; x < 5; x++) {
                    C[x] = s[x] ^ s[x + 5] ^ s[x + 10] ^ s[x + 15] ^ s[x + 20];
                }
                for (int x = 0; x < 5; x++) {
                    D[x] = C[(x + 4) % 5] ^ Long.rotateLeft(C[(x + 1) % 5], 1);
                }
                for (int i = 0; i < 25; i++) {
                    s[i] ^= D[i % 5];
                }

                // Rho + Pi
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 5; y++) {
                        final int idx = x + 5 * y;
                        final int newIdx = y + 5 * ((2 * x + 3 * y) % 5);
                        B[newIdx] = Long.rotateLeft(s[idx], R[idx]);
                    }
                }

                // Chi
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        s[x + 5 * y] = B[x + 5 * y] ^ ((~B[((x + 1) % 5) + 5 * y]) & B[((x + 2) % 5) + 5 * y]);
                    }
                }

                // Iota
                s[0] ^= RC[round];
            }
        }
    }
}
