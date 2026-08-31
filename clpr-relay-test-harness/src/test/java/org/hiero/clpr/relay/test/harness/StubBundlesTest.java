// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.clpr.relay.core.BundlePayloadCodec;
import org.hiero.clpr.relay.core.HieroProofCodec;
import org.hiero.clpr.relay.core.ParsedBundle;
import org.junit.jupiter.api.Test;

/**
 * No-Docker checks that {@link StubBundles} emits Hiero {@code StateProof} bytes the production
 * {@code CompositeProofParser} accepts, and that the {@code ClprChannel} leaf maps to the intended
 * {@code ClprQueueMetadata}. Contract-acceptance (running-hash/replay/ack against the live ledger) is
 * exercised separately by the Anvil-backed pilot.
 */
class StubBundlesTest {

    private final BundlePayloadCodec codec = new HieroProofCodec();
    private final Bytes channelId = Bytes.wrap(new byte[32]);

    @Test
    void craftsDataBundleWithIntendedMetadata() {
        final Bytes bundle = StubBundles.hieroStateProof()
                .ackedMessageId(0)
                .receivedMessageId(3)
                .status(ClprChannelStatus.ACTIVE)
                .message(new byte[32], new byte[20], new byte[20], "m1".getBytes())
                .message(new byte[32], new byte[20], new byte[20], "m2".getBytes())
                .build();

        final ParsedBundle vb = codec.decodeBundle(bundle);

        // nextMessageId = acked(0) + 1 + #messages(2)
        assertThat(vb.metadata().nextMessageId()).isEqualTo(3L);
        assertThat(vb.metadata().receivedMessageId()).isEqualTo(3L);
        assertThat(vb.metadata().status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(vb.messages()).hasSize(2);
    }

    @Test
    void statusOnlyEmptyBundleDecodes() {
        final Bytes bundle =
                StubBundles.hieroStateProof().status(ClprChannelStatus.DRAINED).build();

        final ParsedBundle vb = codec.decodeBundle(bundle);

        assertThat(vb.metadata().status()).isEqualTo(ClprChannelStatus.DRAINED);
        assertThat(vb.messages()).isEmpty();
        // nextMessageId = acked(0) + 1 + 0 messages
        assertThat(vb.metadata().nextMessageId()).isEqualTo(1L);
    }

    @Test
    void replyBundleDecodesAsReply() {
        final Bytes bundle = StubBundles.hieroStateProof()
                .ackedMessageId(0)
                .reply(1L, ClprMessageReplyStatus.SUCCESS, "ok".getBytes())
                .build();

        final ParsedBundle vb = codec.decodeBundle(bundle);

        assertThat(vb.messages()).hasSize(1);
        assertThat(vb.messages().get(0).hasMessageReply()).isTrue();
        assertThat(vb.messages().get(0).messageReply().messageId()).isEqualTo(1L);
    }

    @Test
    void controlConfigUpdateDecodesAsControlMessage() {
        final ClprLedgerConfiguration config = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(1)
                .chainId("eip155:1337")
                .build();
        final Bytes bundle = StubBundles.hieroStateProof()
                .ackedMessageId(0)
                .controlConfigUpdate(config)
                .build();

        final ParsedBundle vb = codec.decodeBundle(bundle);

        assertThat(vb.messages()).hasSize(1);
        assertThat(vb.messages().get(0).hasControl()).isTrue();
        assertThat(vb.messages().get(0).control().configUpdate().configuration().chainId())
                .isEqualTo("eip155:1337");
    }

    @Test
    void runningHashChainsDeterministicallyFromBase() {
        // Same inputs → identical bytes (the running-hash chain is deterministic), so a re-crafted
        // bundle is byte-identical — the property the dedup test relies on.
        final Bytes a = StubBundles.hieroStateProof()
                .message(new byte[32], new byte[20], new byte[20], "x".getBytes())
                .build();
        final Bytes b = StubBundles.hieroStateProof()
                .message(new byte[32], new byte[20], new byte[20], "x".getBytes())
                .build();
        assertThat(a).isEqualTo(b);
    }
}
