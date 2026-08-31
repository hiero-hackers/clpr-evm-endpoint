// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;

/**
 * Assertions over a channel's on-chain state — the ground-truth surface read through the same
 * {@link ContractStateReader} the endpoint-under-test uses. Most cross-surface lifecycle assertions pair
 * this (the contract transitioned correctly) with {@link QueueMetadataAssert} (the endpoint told the peer
 * so).
 */
public final class ChannelStateAssert {

    private static final long POLL_INTERVAL_MS = 50L;

    private final ContractStateReader reader;
    private final Bytes channelId;
    private final String blockTag;
    private Duration timeout = Duration.ZERO;

    ChannelStateAssert(final ContractStateReader reader, final byte[] channelId) {
        this.reader = reader;
        this.channelId = Bytes.wrap(channelId.clone());
        this.blockTag = CommitmentLevel.LATEST.toBlockTag();
    }

    /** Poll for up to {@code timeout} until the asserted condition holds (default: assert immediately). */
    public ChannelStateAssert eventually(final Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public ChannelStateAssert isPresent() {
        awaitOptional(Optional::isPresent, "channel record present");
        return this;
    }

    public ChannelStateAssert isAbsent() {
        awaitOptional(Optional::isEmpty, "channel record absent");
        return this;
    }

    public ChannelStateAssert hasStatus(final ClprChannelStatus expected) {
        return check(ClprChannel::status, expected, "status");
    }

    public ChannelStateAssert hasNextMessageId(final long expected) {
        return check(ClprChannel::nextMessageId, expected, "next_message_id");
    }

    public ChannelStateAssert hasReceivedMessageId(final long expected) {
        return check(ClprChannel::receivedMessageId, expected, "received_message_id");
    }

    public ChannelStateAssert hasAckedMessageId(final long expected) {
        return check(ClprChannel::ackedMessageId, expected, "acked_message_id");
    }

    private <T> ChannelStateAssert check(final Function<ClprChannel, T> getter, final T expected, final String field) {
        final ClprChannel last = awaitChannel(c -> expected.equals(getter.apply(c)));
        final T actual = getter.apply(last);
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "Expected channel " + hex() + '.' + field + " to be <" + expected + "> but was <" + actual + ">");
        }
        return this;
    }

    /** Poll until the predicate holds on a present record or the timeout elapses; return the last read. */
    private ClprChannel awaitChannel(final Predicate<ClprChannel> predicate) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        ClprChannel last = null;
        while (true) {
            final Optional<ClprChannel> current = reader.readChannelState(channelId, blockTag);
            if (current.isPresent()) {
                last = current.get();
                if (predicate.test(last)) {
                    return last;
                }
            }
            if (System.nanoTime() >= deadline) {
                if (last == null) {
                    throw new AssertionError("Channel " + hex() + " has no on-chain record");
                }
                return last; // let the caller render the value mismatch
            }
            sleep();
        }
    }

    private void awaitOptional(final Predicate<Optional<ClprChannel>> predicate, final String description) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        Optional<ClprChannel> current;
        while (true) {
            current = reader.readChannelState(channelId, blockTag);
            if (predicate.test(current)) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Channel " + hex() + ": expected " + description + " but was "
                        + (current.isPresent() ? "present" : "absent"));
            }
            sleep();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling channel state", e);
        }
    }

    private String hex() {
        return HexFormat.of().formatHex(channelId.toByteArray());
    }
}
