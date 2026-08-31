// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the chain-agnostic message-selection arithmetic in {@link BundleTrimmer#trim}. */
class BundleTrimmerTest {

    private static ClprMessagePayload msg(final String data) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap(data.getBytes()))
                        .build())
                .build();
    }

    @Test
    void returnsSameInstanceWhenNothingAlreadyReceived() {
        // ids [1,2,3]; receiver has 0 — nothing to drop.
        final List<ClprMessagePayload> messages = List.of(msg("m1"), msg("m2"), msg("m3"));

        final List<ClprMessagePayload> kept = BundleTrimmer.trim(messages, 4L, 0L);

        // Reference equality lets callers short-circuit to verbatim submission.
        assertThat(kept).isSameAs(messages);
    }

    @Test
    void dropsLeadingAlreadyReceivedMessages() {
        // ids [3,4,5]; receiver already has up to 4 — keep only message 5.
        final List<ClprMessagePayload> messages = List.of(msg("m3"), msg("m4"), msg("m5"));

        final List<ClprMessagePayload> kept = BundleTrimmer.trim(messages, 6L, 4L);

        assertThat(kept).isNotSameAs(messages);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).message().messageData()).isEqualTo(Bytes.wrap("m5".getBytes()));
    }

    @Test
    void dropsAllMessagesWhenEntireBundleAlreadyReceived() {
        // ids [1]; receiver already has 1 — fully trimmed to an ack-only bundle.
        final List<ClprMessagePayload> messages = List.of(msg("m1"));

        assertThat(BundleTrimmer.trim(messages, 2L, 1L)).isEmpty();
    }

    @Test
    void clampsWhenReceivedIdRunsPastTheBundle() {
        // ids [3,4,5]; receiver claims 99 — clamp to dropping every message, never past the end.
        final List<ClprMessagePayload> messages = List.of(msg("m3"), msg("m4"), msg("m5"));

        assertThat(BundleTrimmer.trim(messages, 6L, 99L)).isEmpty();
    }

    @Test
    void returnsSameInstanceForAckOnlyBundle() {
        // No messages to begin with — trimming is a no-op regardless of receivedMessageId.
        final List<ClprMessagePayload> messages = List.of();

        assertThat(BundleTrimmer.trim(messages, 5L, 10L)).isSameAs(messages);
    }
}
