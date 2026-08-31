// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EvmUtilsTest {

    // ── toSlotHex ─────────────────────────────────────────────────────────────

    @Nested
    class ToSlotHex {

        @Test
        void padsLeadingZeroNibble() {
            // 0xa has a single significant nibble; the old encoder emitted "0xa" (odd length),
            // which strict eth_getProof nodes reject. It must be left-padded to a full 32 bytes.
            final String hex = EvmUtils.toSlotHex(BigInteger.valueOf(0xAL));

            assertThat(hex).isEqualTo("0x" + "0".repeat(63) + "a");
            assertThat(hex).hasSize(66); // "0x" + 64 hex chars
        }

        @Test
        void padsShortValueTo32Bytes() {
            // A 31-byte value (62 significant hex chars) — even length but only 31 bytes.
            final BigInteger slot = BigInteger.ONE.shiftLeft(8 * 31).subtract(BigInteger.ONE);
            final String hex = EvmUtils.toSlotHex(slot);

            assertThat(hex).startsWith("0x");
            assertThat(hex.substring(2)).hasSize(64).isEqualTo("00" + "f".repeat(62));
        }

        @Test
        void fullWidthValueUnchangedDigits() {
            // Already 32 bytes — no padding needed, digits must be preserved verbatim.
            assertThat(EvmUtils.toSlotHex(BigInteger.ONE.shiftLeft(255))).isEqualTo("0x8" + "0".repeat(63));

            final BigInteger maxSlot = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE); // 2^256 - 1
            assertThat(EvmUtils.toSlotHex(maxSlot)).isEqualTo("0x" + "f".repeat(64));
        }

        @Test
        void alwaysProduces64HexChars() {
            final List<BigInteger> fixed = List.of(
                    BigInteger.ZERO,
                    BigInteger.ONE,
                    BigInteger.valueOf(0xAL),
                    BigInteger.valueOf(0xFFL),
                    BigInteger.ONE.shiftLeft(255),
                    BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));
            for (final BigInteger slot : fixed) {
                assertThat(EvmUtils.toSlotHex(slot)).matches("0x[0-9a-f]{64}");
            }

            final Random random = new Random(0xC0FFEEL);
            for (int i = 0; i < 256; i++) {
                final BigInteger slot = new BigInteger(256, random); // [0, 2^256 - 1]
                assertThat(EvmUtils.toSlotHex(slot)).matches("0x[0-9a-f]{64}");
            }
        }

        @Test
        void rejectsOversizedSlot() {
            // toSlotHex delegates its bounds checks to AbiCodec.encodeUint256.
            assertThatThrownBy(() -> EvmUtils.toSlotHex(BigInteger.ONE.shiftLeft(256)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("256 bits");
        }

        @Test
        void rejectsNegativeSlot() {
            assertThatThrownBy(() -> EvmUtils.toSlotHex(BigInteger.valueOf(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }

    // ── wrapToUint256 (EVM slot wraparound) ───────────────────────────────────

    @Nested
    class WrapToUint256 {

        @Test
        void leavesInRangeValuesUnchanged() {
            final BigInteger max = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE); // 2^256 - 1
            assertThat(EvmUtils.wrapToUint256(BigInteger.ZERO)).isEqualTo(BigInteger.ZERO);
            assertThat(EvmUtils.wrapToUint256(BigInteger.valueOf(0xAL))).isEqualTo(BigInteger.valueOf(0xAL));
            assertThat(EvmUtils.wrapToUint256(max)).isEqualTo(max);
        }

        @Test
        void wrapsValuesPast256Bits() {
            final BigInteger twoPow256 = BigInteger.ONE.shiftLeft(256);
            assertThat(EvmUtils.wrapToUint256(twoPow256)).isEqualTo(BigInteger.ZERO);
            assertThat(EvmUtils.wrapToUint256(twoPow256.add(BigInteger.valueOf(4))))
                    .isEqualTo(BigInteger.valueOf(4));
        }

        @Test
        void nearMaxHashPlusOffsetWrapsInsteadOfThrowing() {
            // Worst case: keccak256(...) at its ceiling (all-0xFF = 2^256 - 1) plus the largest
            // channel-field offset (5). Plain addition is 2^256 + 4 — a 257-bit value toSlotHex
            // would reject. wrapToUint256 reduces it to 4, rendering a valid 32-byte key, no throw.
            final BigInteger maxHash = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
            final BigInteger wrapped = EvmUtils.wrapToUint256(maxHash.add(BigInteger.valueOf(5)));

            assertThat(wrapped).isEqualTo(BigInteger.valueOf(4));
            assertThatNoException().isThrownBy(() -> EvmUtils.toSlotHex(wrapped));
            assertThat(EvmUtils.toSlotHex(wrapped)).isEqualTo("0x" + "0".repeat(63) + "4");
        }
    }
}
