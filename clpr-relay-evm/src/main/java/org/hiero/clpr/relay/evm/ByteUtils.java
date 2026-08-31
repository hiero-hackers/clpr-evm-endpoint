// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import com.hedera.pbj.runtime.io.buffer.Bytes;

public class ByteUtils {
    public static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    public static Bytes leftPad32(final Bytes bytes) {
        final int n = (int) bytes.length();
        if (n >= 32) {
            return bytes;
        }
        final var padded = new byte[32];
        bytes.getBytes(0, padded, 32 - n, n);
        return Bytes.wrap(padded);
    }

    public static Bytes fromPrefixedHex(String hex) {
        // Strip the 0x/0X prefix once at the front (don't blindly remove all "0x" — a value
        // could legitimately contain those two bytes embedded as data).
        String stripped = hex;
        if (stripped.startsWith("0x") || stripped.startsWith("0X")) {
            stripped = stripped.substring(2);
        }
        // Besu / web3 JSON-RPC frequently returns "canonical" hex with no leading zero on
        // odd-nibble values (e.g. balance "0x0", value "0x123"). Bytes.fromHex requires an
        // even-length string, so left-pad with a single '0' nibble when needed.
        if ((stripped.length() & 1) != 0) {
            stripped = "0" + stripped;
        }
        return Bytes.fromHex(stripped);
    }
}
