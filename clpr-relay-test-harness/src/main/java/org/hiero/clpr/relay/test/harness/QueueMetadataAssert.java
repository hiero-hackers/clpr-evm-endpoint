// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Objects;

/**
 * Assertions over the {@link ClprQueueMetadata} a peer observes in a bundle — the "peer-observed" surface
 * that proves the endpoint-under-test reflected its ledger state into what it tells peers (running hash,
 * status propagation, ack frontier), which on-chain state alone cannot reveal.
 *
 * <p>Failures throw {@link AssertionError}, so JUnit/AssertJ treat them as ordinary assertion failures;
 * the harness deliberately carries no test-framework dependency in its main sources.
 */
public final class QueueMetadataAssert {

    private final ClprQueueMetadata metadata;

    QueueMetadataAssert(final ClprQueueMetadata metadata) {
        this.metadata = metadata;
    }

    /** The verified metadata under assertion. */
    public ClprQueueMetadata metadata() {
        return metadata;
    }

    public QueueMetadataAssert hasStatus(final ClprChannelStatus expected) {
        return require(metadata.status() == expected, "metadata.status", expected, metadata.status());
    }

    public QueueMetadataAssert hasNextMessageId(final long expected) {
        return require(
                metadata.nextMessageId() == expected, "metadata.next_message_id", expected, metadata.nextMessageId());
    }

    public QueueMetadataAssert hasReceivedMessageId(final long expected) {
        return require(
                metadata.receivedMessageId() == expected,
                "metadata.received_message_id",
                expected,
                metadata.receivedMessageId());
    }

    public QueueMetadataAssert hasSentRunningHash(final Bytes expected) {
        return require(
                Objects.equals(metadata.sentRunningHash(), expected),
                "metadata.sent_running_hash",
                expected,
                metadata.sentRunningHash());
    }

    public QueueMetadataAssert hasReceivedRunningHash(final Bytes expected) {
        return require(
                Objects.equals(metadata.receivedRunningHash(), expected),
                "metadata.received_running_hash",
                expected,
                metadata.receivedRunningHash());
    }

    public QueueMetadataAssert hasTrustAnchorId(final Bytes expected) {
        return require(
                Objects.equals(metadata.trustAnchorId(), expected),
                "metadata.trust_anchor_id",
                expected,
                metadata.trustAnchorId());
    }

    private QueueMetadataAssert require(
            final boolean ok, final String field, final Object expected, final Object actual) {
        if (!ok) {
            throw new AssertionError("Expected " + field + " to be <" + expected + "> but was <" + actual + ">");
        }
        return this;
    }
}
