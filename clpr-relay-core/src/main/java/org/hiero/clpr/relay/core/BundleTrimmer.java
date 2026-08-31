// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import java.util.List;

/**
 * Drops leading messages a receiving ledger has already accepted from a decoded bundle's message list.
 *
 * <p>This is the chain-agnostic half of replay-trimming: the message-selection arithmetic is identical
 * for every proof format, so it lives here rather than being duplicated in each
 * {@link BundlePayloadCodec}. Each codec calls this to pick the retained suffix, then re-encodes that
 * suffix back into its own proof wire format.
 *
 * <p>Message ids are positional: a bundle whose metadata frontier is {@code nextMessageId} and which
 * carries {@code n} messages covers ids {@code [nextMessageId - n .. nextMessageId - 1]}. Any leading
 * message whose id is {@code <= receivedMessageId} is already on the receiving ledger and is dropped;
 * the contiguous suffix with strictly-greater ids is retained.
 */
public final class BundleTrimmer {

    private BundleTrimmer() {}

    /**
     * Returns the retained suffix of {@code messages} after dropping every leading message the receiving
     * ledger has already accepted.
     *
     * <p>When nothing is dropped the <b>same</b> list instance is returned, so callers can short-circuit
     * with a reference check ({@code kept == messages}) and submit the peer's bytes verbatim instead of
     * re-encoding an unchanged bundle.
     *
     * @param messages the bundle's ordered message payloads
     * @param nextMessageId the bundle metadata frontier — one past the last message id in the bundle
     * @param receivedMessageId the highest message id the receiving ledger has already accepted;
     *     messages with id {@code <= receivedMessageId} are dropped
     * @return {@code messages} itself when nothing was dropped, otherwise a new list holding only the
     *     retained (strictly-greater-id) suffix
     */
    public static List<ClprMessagePayload> trim(
            final List<ClprMessagePayload> messages, final long nextMessageId, final long receivedMessageId) {
        // Message ids are positional: the bundle covers [nextMessageId - size .. nextMessageId - 1].
        final long firstId = nextMessageId - messages.size();
        // Leading messages whose id is <= receivedMessageId are already on the receiving ledger.
        final int dropCount = (int) Math.clamp(receivedMessageId - firstId + 1L, 0L, messages.size());
        if (dropCount == 0) {
            return messages;
        }
        return List.copyOf(messages.subList(dropCount, messages.size()));
    }
}
