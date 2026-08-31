// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests that {@link StubReencodingTransactionSubmitter} rewrites the proof into CLPRSTUB form. */
class StubReencodingTransactionSubmitterTest {

    @Test
    void reencodesParsedBundleIntoClprStubFormatBeforeDelegating() throws Exception {
        final CapturingSubmitter delegate = new CapturingSubmitter();
        final var submitter = new StubReencodingTransactionSubmitter(delegate);

        final ClprQueueMetadata metadata =
                ClprQueueMetadata.newBuilder().nextMessageId(5L).build();
        final ClprMessagePayload payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap("hi".getBytes()))
                        .build())
                .build();
        // Inbound raw proof is a (fake) non-stub QBFT payload — RLP list prefix 0xc1.
        final ParsedBundle inbound =
                new ParsedBundle(metadata, List.of(payload), Bytes.wrap(new byte[] {(byte) 0xc1, 0x01}));
        submitter.submitBundle(
                ClprChannel.newBuilder().channelId(Bytes.wrap(new byte[32])).build(), inbound);

        final ParsedBundle submitted = delegate.last;
        final byte[] raw = submitted.rawProofBytes().toByteArray();
        assertThat(raw).startsWith("CLPRSTUB".getBytes());

        // The bytes after the 8-byte prefix decode back to the same bundle content.
        final Bytes proto = submitted.rawProofBytes().slice(8, raw.length - 8);
        final ClprBundleContent decoded = ClprBundleContent.PROTOBUF.parse(proto);
        assertThat(decoded.metadata().nextMessageId()).isEqualTo(5L);
        assertThat(decoded.messages()).hasSize(1);
        assertThat(decoded.messages().get(0).message().messageData()).isEqualTo(Bytes.wrap("hi".getBytes()));

        // Metadata and messages are preserved on the ParsedBundle that reaches the delegate.
        assertThat(submitted.metadata().nextMessageId()).isEqualTo(5L);
        assertThat(submitted.messages()).hasSize(1);
    }

    /** Captures the last {@link ParsedBundle} passed to {@code submitBundle}. */
    private static final class CapturingSubmitter implements TransactionSubmitter {
        private ParsedBundle last;

        @Override
        public void submitBundle(final ClprChannel channel, final ParsedBundle verified) {
            this.last = verified;
        }
    }
}
