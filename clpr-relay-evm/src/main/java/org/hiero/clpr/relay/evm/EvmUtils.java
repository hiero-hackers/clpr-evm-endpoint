// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import java.math.BigInteger;
import java.util.HexFormat;

/**
 * EVM utilities
 */
public final class EvmUtils {

    /**
     * Modulus for EVM storage-slot arithmetic. Solidity addresses mapping/struct slots with
     * wrapping {@code uint256} addition — the {@code ADD} opcode drops any carry past bit 256 — so
     * {@code keccak256(...) + fieldOffset} is reduced mod 2^256 to match the slot the contract
     * actually uses in the (astronomically unlikely) overflow case rather than producing an
     * out-of-range value.
     */
    static final BigInteger TWO_POW_256 = BigInteger.ONE.shiftLeft(256);

    private EvmUtils() {}

    /**
     * Renders a storage slot index as a {@code 0x}-prefixed, zero-padded 32-byte (64-hex-char)
     * string, as required by {@code eth_getProof} storage keys (EIP-1186 DATA). Delegates the
     * fixed-width big-endian encoding — and its non-negative / 256-bit bounds checks — to
     * {@link AbiCodec#encodeUint256}.
     */
    public static String toSlotHex(final BigInteger slot) {
        return "0x" + HexFormat.of().formatHex(AbiCodec.encodeUint256(slot));
    }

    /**
     * Reduces a computed storage slot to a {@code uint256}, reproducing the EVM's wrapping slot
     * arithmetic. {@code keccak256(...)} is already in {@code [0, 2^256 - 1]}; adding a small field
     * offset can in principle carry past bit 256, which the EVM silently wraps. Wrapping here keeps
     * the requested slot identical to the one the contract addresses, so the slot never exceeds 32
     * bytes and {@link #toSlotHex} never has to reject it.
     */
    public static BigInteger wrapToUint256(final BigInteger slot) {
        return slot.mod(TWO_POW_256);
    }

    /** Writes {@code value} as a 32-byte big-endian unsigned integer into {@code buf} at {@code offset}. */
    public static void toUint256Bytes(final BigInteger value, final byte[] buf, final int offset) {
        System.arraycopy(AbiCodec.encodeUint256(value), 0, buf, offset, 32);
    }
}
