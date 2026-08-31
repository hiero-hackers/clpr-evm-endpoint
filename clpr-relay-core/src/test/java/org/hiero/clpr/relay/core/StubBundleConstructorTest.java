// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubBundleConstructorTest {

    private static final Bytes MAGIC_PREFIX = Bytes.wrap("CLPRSTUB".getBytes());

    private StubBundleConstructor constructor;
    private Bytes channelId1;
    private Bytes channelId2;

    @BeforeEach
    void setUp() {
        constructor = new StubBundleConstructor();
        channelId1 = Bytes.wrap(new byte[32]); // all-zeros 32-byte ID
        byte[] id2bytes = new byte[32];
        id2bytes[0] = 1;
        channelId2 = Bytes.wrap(id2bytes);
    }

    @Test
    void returnsEmptyBeforeStateChange() {
        Optional<Bytes> bundleProof = constructor.getLatestBundlePayload(channelId1);
        assertThat(bundleProof).isEmpty();
    }

    @Test
    void returnsCachedProofAfterStateChange() {
        ClprChannel channel = ClprChannel.DEFAULT;
        List<ContractStateReader.QueuedMessage> messages = List.of();

        constructor.onStateChanged(BigInteger.ONE, channelId1, channel, messages);

        Optional<Bytes> bundleProof = constructor.getLatestBundlePayload(channelId1);

        assertThat(bundleProof).isPresent();

        // Verify magic prefix is present
        byte[] bundleBytes = bundleProof.get().toByteArray();
        byte[] prefixBytes = MAGIC_PREFIX.toByteArray();
        assertThat(bundleBytes.length).isGreaterThanOrEqualTo(prefixBytes.length);
        for (int i = 0; i < prefixBytes.length; i++) {
            assertThat(bundleBytes[i]).isEqualTo(prefixBytes[i]);
        }
    }

    @Test
    void differentChannelsGetDifferentCaches() {
        ClprChannel channel = ClprChannel.DEFAULT;
        List<ContractStateReader.QueuedMessage> messages = List.of();

        // Only update conn1
        constructor.onStateChanged(BigInteger.ONE, channelId1, channel, messages);

        // conn1 should have a proof
        assertThat(constructor.getLatestBundlePayload(channelId1)).isPresent();

        // conn2 should still be empty
        assertThat(constructor.getLatestBundlePayload(channelId2)).isEmpty();
    }

    @Test
    void updatesProofOnSubsequentStateChanges() {
        ClprChannel channel = ClprChannel.DEFAULT;

        // First state change with no messages
        constructor.onStateChanged(BigInteger.ONE, channelId1, channel, List.of());
        Bytes firstBundleProof = constructor.getLatestBundlePayload(channelId1).orElseThrow();

        // Second state change with a message
        ClprMessagePayload payload = ClprMessagePayload.DEFAULT;
        ClprMessageValue messageValue =
                ClprMessageValue.newBuilder().payload(payload).build();
        ContractStateReader.QueuedMessage queuedMessage =
                new ContractStateReader.QueuedMessage(BigInteger.ONE, messageValue);
        constructor.onStateChanged(BigInteger.ONE, channelId1, channel, List.of(queuedMessage));

        Bytes secondBundleProof = constructor.getLatestBundlePayload(channelId1).orElseThrow();

        // Bundle proof should change since messages changed
        assertThat(secondBundleProof).isNotEqualTo(firstBundleProof);
    }
}
