// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Round-trip tests for {@link RlpReader} against the {@link AbiCodec} RLP encode helpers. */
class RlpReaderTest {

    @Test
    void splitsTopLevelItemsAndReadsByteStrings() {
        final byte[] shortStr = {0x11, 0x22};
        final byte[] longStr = new byte[100]; // forces the long-string (0xb8+) header
        Arrays.fill(longStr, (byte) 0xAB);

        final byte[] encoded = AbiCodec.rlpList(
                AbiCodec.rlpBytes(shortStr),
                AbiCodec.rlpBytes(longStr),
                AbiCodec.rlpList(AbiCodec.rlpBytes(new byte[] {0x01}))); // a nested list item

        final List<Bytes> items = RlpReader.splitList(Bytes.wrap(encoded));

        assertThat(items).hasSize(3);
        assertThat(RlpReader.bytesValue(items.get(0)).toByteArray()).containsExactly(shortStr);
        assertThat(RlpReader.bytesValue(items.get(1)).toByteArray()).containsExactly(longStr);
    }

    @Test
    void singleByteStringRoundTrips() {
        final byte[] one = {0x7f}; // encoded as itself (< 0x80), no header byte
        final List<Bytes> items = RlpReader.splitList(Bytes.wrap(AbiCodec.rlpList(AbiCodec.rlpBytes(one))));

        assertThat(items).hasSize(1);
        assertThat(RlpReader.bytesValue(items.get(0)).toByteArray()).containsExactly(one);
    }

    @Test
    void emptyByteStringRoundTrips() {
        final List<Bytes> items = RlpReader.splitList(Bytes.wrap(AbiCodec.rlpList(AbiCodec.rlpBytes(new byte[0]))));

        assertThat(items).hasSize(1);
        assertThat(RlpReader.bytesValue(items.get(0)).toByteArray()).isEmpty();
    }

    @Test
    void splitListRejectsAStringInput() {
        final byte[] str = AbiCodec.rlpBytes(new byte[] {1, 2, 3});
        assertThatThrownBy(() -> RlpReader.splitList(Bytes.wrap(str)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list");
    }

    @Test
    void bytesValueRejectsAListItem() {
        final byte[] list = AbiCodec.rlpList(AbiCodec.rlpBytes(new byte[] {1}));
        assertThatThrownBy(() -> RlpReader.bytesValue(Bytes.wrap(list)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("string");
    }

    @Test
    void rejectsTrailingBytesAfterList() {
        final byte[] good = AbiCodec.rlpList(AbiCodec.rlpBytes(new byte[] {1}));
        final byte[] withTrailer = Arrays.copyOf(good, good.length + 1);
        assertThatThrownBy(() -> RlpReader.splitList(Bytes.wrap(withTrailer)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
