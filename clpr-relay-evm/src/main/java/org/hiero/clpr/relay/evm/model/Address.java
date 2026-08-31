// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.hiero.clpr.relay.evm.AbiCodec;

public record Address(byte[] value) {

    public Address {
        if (value.length != 20) {
            throw new IllegalArgumentException("Expected 20 bytes, got " + value.length);
        }
    }

    public static Address fromHexString(final String hex) {
        final String stripped = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (stripped.length() != 40) {
            throw new IllegalArgumentException("Expected 40 hex chars, got " + stripped.length());
        }
        final byte[] bytes = new byte[20];
        for (int i = 0; i < 20; i++) {
            bytes[i] = (byte) Integer.parseInt(stripped, i * 2, i * 2 + 2, 16);
        }
        return new Address(bytes);
    }

    public static final Address ZERO = new Address(new byte[20]);

    /**
     * Returns the EIP-55 mixed-case checksum address.
     */
    public String toChecksumHexString() {
        final String lower = toHexString().substring(2); // strip 0x
        final byte[] hash = AbiCodec.Keccak256.keccak256(lower.getBytes(StandardCharsets.US_ASCII));
        final StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < 40; i++) {
            final char c = lower.charAt(i);
            final int nibble = (hash[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0xF;
            sb.append(nibble >= 8 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    public String toHexString() {
        final StringBuilder sb = new StringBuilder("0x");
        for (final byte b : value) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Address other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    /** Returns the EIP-55 checksum form by default. */
    @Override
    public String toString() {
        return toChecksumHexString();
    }
}
