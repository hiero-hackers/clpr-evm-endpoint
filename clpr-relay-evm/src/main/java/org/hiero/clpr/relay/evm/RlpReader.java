// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Minimal RLP decoder — the read counterpart to the RLP encode helpers in {@link AbiCodec}
 * ({@code rlpBytes} / {@code rlpList}).
 *
 * <p>Only the operations the relay needs to read a Besu-QBFT bundle payload are implemented:
 * splitting a top-level list into its items and reading a byte-string item's content. It does not
 * recurse into nested lists — callers decode only the items they need, which keeps a single inbound
 * decode from walking the (large) block header and proof-node sub-structures unnecessarily.
 *
 * @see <a href="https://ethereum.org/en/developers/docs/data-structures-and-encoding/rlp/">RLP spec</a>
 */
public final class RlpReader {

    private RlpReader() {}

    /**
     * Split an RLP-encoded list into its top-level items, each returned as its full raw RLP encoding
     * (header + payload). Does not recurse into the items.
     *
     * @param input the RLP encoding of a list
     * @return the raw encodings of the list's items, in order
     * @throws IllegalArgumentException if {@code input} is not a well-formed RLP list
     */
    @NonNull
    public static List<Bytes> splitList(@NonNull final Bytes input) {
        final byte[] data = input.toByteArray();
        final Header header = readHeader(data, 0);
        if (!header.isList) {
            throw new IllegalArgumentException("Expected an RLP list, got a string item");
        }
        final int end = header.payloadOffset + header.payloadLength;
        if (end != data.length) {
            throw new IllegalArgumentException(
                    "Trailing bytes after RLP list (consumed " + end + " of " + data.length + ")");
        }
        final List<Bytes> items = new ArrayList<>();
        int pos = header.payloadOffset;
        while (pos < end) {
            final Header itemHeader = readHeader(data, pos);
            final int itemEnd = itemHeader.payloadOffset + itemHeader.payloadLength;
            if (itemEnd > end) {
                throw new IllegalArgumentException("RLP item overruns its enclosing list");
            }
            items.add(Bytes.wrap(Arrays.copyOfRange(data, pos, itemEnd)));
            pos = itemEnd;
        }
        return items;
    }

    /**
     * Read the content bytes of an RLP-encoded byte string.
     *
     * @param item the raw RLP encoding of a string item
     * @return the decoded content
     * @throws IllegalArgumentException if {@code item} is a list rather than a string
     */
    @NonNull
    public static Bytes bytesValue(@NonNull final Bytes item) {
        final byte[] data = item.toByteArray();
        final Header header = readHeader(data, 0);
        if (header.isList) {
            throw new IllegalArgumentException("Expected an RLP string, got a list item");
        }
        return Bytes.wrap(Arrays.copyOfRange(data, header.payloadOffset, header.payloadOffset + header.payloadLength));
    }

    /** Decoded item header: where its payload starts, how long it is, and whether it is a list. */
    private record Header(int payloadOffset, int payloadLength, boolean isList) {}

    private static Header readHeader(final byte[] data, final int pos) {
        if (pos >= data.length) {
            throw new IllegalArgumentException("RLP underflow at offset " + pos);
        }
        final int prefix = data[pos] & 0xff;
        if (prefix < 0x80) {
            // Single byte in [0x00, 0x7f] — the byte is its own content.
            return new Header(pos, 1, false);
        } else if (prefix < 0xb8) {
            final int len = prefix - 0x80;
            ensure(data, pos + 1, len);
            return new Header(pos + 1, len, false);
        } else if (prefix < 0xc0) {
            final int lenOfLen = prefix - 0xb7;
            final int len = readLength(data, pos + 1, lenOfLen);
            ensure(data, pos + 1 + lenOfLen, len);
            return new Header(pos + 1 + lenOfLen, len, false);
        } else if (prefix < 0xf8) {
            final int len = prefix - 0xc0;
            ensure(data, pos + 1, len);
            return new Header(pos + 1, len, true);
        } else {
            final int lenOfLen = prefix - 0xf7;
            final int len = readLength(data, pos + 1, lenOfLen);
            ensure(data, pos + 1 + lenOfLen, len);
            return new Header(pos + 1 + lenOfLen, len, true);
        }
    }

    private static int readLength(final byte[] data, final int pos, final int lenOfLen) {
        if (lenOfLen <= 0 || lenOfLen > 4) {
            throw new IllegalArgumentException("Invalid RLP length-of-length: " + lenOfLen);
        }
        ensure(data, pos, lenOfLen);
        int len = 0;
        for (int i = 0; i < lenOfLen; i++) {
            len = (len << 8) | (data[pos + i] & 0xff);
        }
        if (len < 0) {
            throw new IllegalArgumentException("RLP length exceeds Integer.MAX_VALUE");
        }
        return len;
    }

    private static void ensure(final byte[] data, final int pos, final int len) {
        if (len < 0 || pos + len > data.length) {
            throw new IllegalArgumentException("RLP item length exceeds input bounds");
        }
    }
}
