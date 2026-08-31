// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.hiero.clpr.relay.e2e.channel.ChannelSide;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpoint;
import org.jspecify.annotations.Nullable;

/**
 * Waits for asynchronous CLPR outcomes, with the failure messages a stuck channel needs.
 *
 * <p>Every wait reports the last observed state on timeout. "Condition not met within PT3M" is nearly
 * useless for a distributed system; "received watermark stalled at 8 of 25" names the bug.
 *
 * <p>Waits also abort early on a hard verification rejection in the relay logs. Without that, a
 * channel that can never make progress still burns the full budget — and a suite of such waits turns
 * a five-minute run into an hour.
 */
public final class Awaiter {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /**
     * Log fragments that mean "this will never succeed". Matched case-insensitively against the
     * endpoints' output while waiting.
     */
    private static final List<String> FATAL_LOG_MARKERS = List.of(
            "CLPR_BUNDLE_VERIFICATION_FAILED",
            "bundle verification failed",
            "ClprInvalidChannelId",
            // The relay caches one bundle per channel and re-sends it verbatim, so a bundle the
            // on-chain preview rejects never becomes acceptable on its own. Observed with a backlog
            // deeper than maxMessagesPerBundle: the bundle carries the first batch but declares the
            // whole queue, and the receiving service rejects it with SlotNotProven forever
            // (clpr-hiero#171). Treating it as fatal turns a nine-minute timeout into seconds.
            "SlotNotProven");

    private final Supplier<List<EvmEndpoint>> endpoints;

    Awaiter(final Supplier<List<EvmEndpoint>> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Wait until the peer has processed every message queued on {@code side}.
     *
     * @param side the sending side
     * @param timeout how long to wait
     */
    public void queueDrained(final ChannelSide side, final Duration timeout) {
        await(
                timeout,
                () -> side.queueDepth() == 0L,
                () -> "queue on " + side.network().id() + " did not drain: depth=" + side.queueDepth() + ", state="
                        + side.state());
    }

    /**
     * Wait until the peer's received watermark reaches {@code messageId}.
     *
     * <p>The watermark is the right oracle rather than a per-message probe: the service advances it
     * only after processing a message, and it is monotonic, so reaching N means all of 1..N were
     * handled in order.
     *
     * @param side the side that <em>sent</em> the messages
     * @param messageId the highest id expected to be processed
     * @param timeout how long to wait
     */
    public void peerReceived(final ChannelSide side, final long messageId, final Duration timeout) {
        final ChannelSide peer = side.peer();
        await(
                timeout,
                () -> peer.state().receivedMessageId() >= messageId,
                () -> "received watermark on " + peer.network().id() + " stalled at "
                        + peer.state().receivedMessageId() + " of " + messageId
                        + " — the relay stopped making progress on this channel");
    }

    /**
     * Wait until every id in {@code messageIds} has been processed by the peer.
     *
     * @param side the sending side
     * @param messageIds the ids to wait for
     * @param timeout how long to wait
     */
    public void peerReceivedAll(final ChannelSide side, final Collection<Long> messageIds, final Duration timeout) {
        final long highest =
                messageIds.stream().mapToLong(Long::longValue).max().orElse(0L);
        peerReceived(side, highest, timeout);
    }

    /**
     * Wait until the queue on {@code side} is at least {@code depth} deep.
     *
     * @param side the side to watch
     * @param depth the depth to reach
     * @param timeout how long to wait
     */
    public void queueDepthAtLeast(final ChannelSide side, final long depth, final Duration timeout) {
        await(
                timeout,
                () -> side.queueDepth() >= depth,
                () -> "queue on " + side.network().id() + " only reached depth " + side.queueDepth() + " of " + depth);
    }

    /**
     * Wait until an endpoint serves {@code /healthz}.
     *
     * @param endpoint the endpoint
     * @param timeout how long to wait
     */
    public void endpointHealthy(final EvmEndpoint endpoint, final Duration timeout) {
        await(timeout, endpoint::isHealthy, () -> "endpoint " + endpoint.id() + " never became healthy");
    }

    /**
     * Wait until an endpoint has run at least one sync cycle for a channel.
     *
     * <p>The readiness signal that {@code /healthz} cannot give: the relay starts its metrics server
     * before wiring channels, and a channel whose on-chain protocol version mismatches is skipped in
     * silence. A cycle attributed to the channel id is the first observable proof it is really live.
     *
     * @param endpoint the endpoint
     * @param channelMetricLabel the channel's {@code channel_id} metric label (unprefixed hex)
     * @param timeout how long to wait
     */
    public void channelLive(final EvmEndpoint endpoint, final String channelMetricLabel, final Duration timeout) {
        await(
                timeout,
                () -> endpoint.metrics().counter("clpr_sync_cycles_total", Map.of("channel_id", channelMetricLabel))
                        > 0L,
                () -> "endpoint " + endpoint.id() + " never ran a sync cycle for channel " + channelMetricLabel
                        + " — the channel was most likely skipped at startup");
    }

    /**
     * Assert a condition holds for a whole window rather than at one instant.
     *
     * <p>For "must keep making progress" and "must stay stuck" claims, which a single sample cannot
     * distinguish from a lucky moment.
     *
     * @param condition the condition
     * @param window how long it must hold
     * @param description what is being asserted, for the failure message
     */
    public void stableFor(final BooleanSupplier condition, final Duration window, final String description) {
        final long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!condition.getAsBoolean()) {
                    throw new AssertionError(description + " stopped holding before " + window + " elapsed");
                }
            } catch (final RuntimeException e) {
                // Same tolerance as await(): a momentary RPC blip is not the condition breaking. Without
                // this the method whose entire purpose is windowed stability would fail on the first
                // hiccup — the opposite of what it claims to check.
            }
            sleep();
        }
    }

    private void await(final Duration timeout, final BooleanSupplier condition, final Supplier<String> failure) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (final RuntimeException e) {
                // A transient RPC blip is "not yet", not a failure — but a persistent one still times
                // out, and the message below will carry whatever the last read produced.
            }
            final String fatal = firstFatalLogMarker();
            if (fatal != null) {
                throw new AssertionError(failure.get() + "\nAborted early: an endpoint logged a fatal marker (" + fatal
                        + "), so this condition can no longer become true.");
            }
            sleep();
        }
        throw new AssertionError(describe(failure) + " (timed out after " + timeout + ")");
    }

    @Nullable
    private String firstFatalLogMarker() {
        for (final EvmEndpoint endpoint : endpoints.get()) {
            for (final String marker : FATAL_LOG_MARKERS) {
                if (endpoint.logs().sawLineContaining(marker)) {
                    return endpoint.id() + ": " + marker;
                }
            }
        }
        return null;
    }

    private static String describe(final Supplier<String> failure) {
        try {
            return failure.get();
        } catch (final RuntimeException e) {
            return "condition not met (and the diagnostic read itself failed: " + e + ")";
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting");
        }
    }
}
