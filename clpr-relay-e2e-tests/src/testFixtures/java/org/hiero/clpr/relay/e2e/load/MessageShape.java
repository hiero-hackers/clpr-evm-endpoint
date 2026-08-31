// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.load;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * What the injected messages look like.
 *
 * @param payloadBytes total payload size; the sequence marker is padded out to this length
 * @param sequential when {@code true} the payload starts with {@code MSG-<n>}, so a delivered message
 *     can be traced back to its position in the run. Worth the few bytes: without it a partial
 *     delivery tells you how many arrived but not which, and "the first ten" versus "ten arbitrary
 *     ones" are very different diagnoses
 */
public record MessageShape(int payloadBytes, boolean sequential) {

    public MessageShape {
        if (payloadBytes < 1) {
            throw new IllegalArgumentException("payloadBytes must be >= 1, got " + payloadBytes);
        }
    }

    /** A small, traceable message: 32 bytes carrying its sequence number. */
    public static MessageShape small() {
        return new MessageShape(32, true);
    }

    /**
     * A message of a given size, still traceable.
     *
     * @param bytes payload size
     * @return the shape
     */
    public static MessageShape of(final int bytes) {
        return new MessageShape(bytes, true);
    }

    /**
     * Render the payload for message {@code index} of a run.
     *
     * @param index zero-based position in the run
     * @return the payload bytes
     */
    public byte[] payload(final int index) {
        final byte[] out = new byte[payloadBytes];
        if (sequential) {
            final byte[] marker = ("MSG-" + index).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(marker, 0, out, 0, Math.min(marker.length, out.length));
        } else {
            Arrays.fill(out, (byte) 0x5a);
        }
        return out;
    }
}
