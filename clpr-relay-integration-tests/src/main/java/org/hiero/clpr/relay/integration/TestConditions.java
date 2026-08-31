// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hiero.clpr.relay.evm.JsonRpcException;
import org.jspecify.annotations.NonNull;

/**
 * Small polling utility for integration tests that need to wait for an asynchronous
 * condition to become true (e.g. a bundle to be mined, a relay to catch up).
 */
public final class TestConditions {

    private TestConditions() {}

    /**
     * Poll {@code condition} until it returns {@code true} or {@code timeout} elapses.
     *
     * @param timeout      the maximum time to wait
     * @param pollInterval the delay between polls
     * @param condition    the condition to evaluate
     * @throws AssertionError if the timeout elapses before the condition becomes true
     */
    public static void awaitCondition(
            @NonNull final Duration timeout,
            @NonNull final Duration pollInterval,
            @NonNull final Supplier<Boolean> condition) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for condition");
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    /**
     * Overload using a default poll interval of 500ms.
     *
     * @param timeout   the maximum time to wait
     * @param condition the condition to evaluate
     */
    public static void awaitCondition(@NonNull final Duration timeout, @NonNull final Supplier<Boolean> condition) {
        awaitCondition(timeout, Duration.ofMillis(500), condition);
    }

    /**
     * Poll until {@code predicate} holds for the channel's status on <em>both</em> sides, or
     * {@code timeout} elapses. Encapsulates the recurring "read both records, unwrap the
     * {@link java.util.Optional}, swallow transient probe failures" boilerplate: an absent record
     * ({@code Optional.empty()}) and a momentary {@link JsonRpcException} / index error both count
     * as "not yet" rather than aborting the wait.
     *
     * @param sideA        one chain's interactor
     * @param sideB        the other chain's interactor
     * @param channelId the 32-byte channel id
     * @param predicate    the per-side condition on the decoded channel status
     * @param timeout      the maximum time to wait
     * @throws AssertionError if the timeout elapses before the predicate holds on both sides
     */
    public static void awaitBothSides(
            @NonNull final ContractInteractor sideA,
            @NonNull final ContractInteractor sideB,
            @NonNull final byte[] channelId,
            @NonNull final Predicate<ContractInteractor.ChannelState> predicate,
            @NonNull final Duration timeout) {
        awaitCondition(timeout, () -> matches(sideA, channelId, predicate) && matches(sideB, channelId, predicate));
    }

    /**
     * Evaluate {@code predicate} against {@code side}'s channel record, treating an absent
     * record and a transient probe failure alike as {@code false}.
     */
    private static boolean matches(
            @NonNull final ContractInteractor side,
            @NonNull final byte[] channelId,
            @NonNull final Predicate<ContractInteractor.ChannelState> predicate) {
        try {
            return side.readChannelState(channelId).map(predicate::test).orElse(false);
        } catch (final JsonRpcException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    /**
     * Poll {@code side.readChannelState(channelId)} until its status equals {@code expected}
     * or {@code timeout} elapses. The {@link AssertionError} on timeout names the last-observed
     * status (or "no Channel record on chain" if the record was absent throughout), so a
     * stalemate-revealing failure carries the stuck status as evidence.
     *
     * @param side          the interactor whose chain to query
     * @param channelId  the 32-byte channel id
     * @param expected      the lifecycle status to wait for
     * @param timeout       the maximum time to wait
     */
    public static void awaitStatus(
            @NonNull final ContractInteractor side,
            @NonNull final byte[] channelId,
            @NonNull final ClprChannelStatus expected,
            @NonNull final Duration timeout) {
        awaitStatusIn(side, channelId, EnumSet.of(expected), timeout);
    }

    /**
     * Poll {@code side.readChannelState(channelId)} until its status is one of
     * {@code accepted} or {@code timeout} elapses. Use this for transitions the relay may race
     * past: e.g. a channel winding down can be observed at DRAINED <em>or</em>, if it rode
     * straight through, at CLOSED — so {@code EnumSet.of(DRAINED, CLOSED)} captures the
     * deterministic "wound down to at least DRAINED" floor without flaking on the faster path.
     *
     * @param side          the interactor whose chain to query
     * @param channelId  the 32-byte channel id
     * @param accepted      the set of acceptable lifecycle statuses
     * @param timeout       the maximum time to wait
     */
    public static void awaitStatusIn(
            @NonNull final ContractInteractor side,
            @NonNull final byte[] channelId,
            @NonNull final Set<ClprChannelStatus> accepted,
            @NonNull final Duration timeout) {
        final Duration pollInterval = Duration.ofMillis(500);
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        ClprChannelStatus lastObserved = null;
        boolean lastObservedAbsent = false;
        Throwable lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final var stateOpt = side.readChannelState(channelId);
                if (stateOpt.isEmpty()) {
                    lastObserved = null;
                    lastObservedAbsent = true;
                    lastError = null;
                } else {
                    lastObservedAbsent = false;
                    lastObserved =
                            ClprChannelStatus.fromProtobufOrdinal(stateOpt.get().status());
                    lastError = null;
                    if (accepted.contains(lastObserved)) {
                        return;
                    }
                }
            } catch (final JsonRpcException | IndexOutOfBoundsException e) {
                lastError = e;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting status in " + accepted);
            }
        }
        final String observedSummary;
        if (lastObserved != null) {
            observedSummary = lastObserved.toString();
        } else if (lastObservedAbsent) {
            observedSummary = "no Channel record on chain (PENDING or unregistered)";
        } else {
            observedSummary =
                    "never observed (last error: " + (lastError == null ? "n/a" : lastError.getMessage()) + ")";
        }
        final String target = accepted.size() == 1 ? accepted.iterator().next().toString() : "one of " + accepted;
        throw new AssertionError("Channel status did not reach " + target + " within " + timeout + "; last observed: "
                + observedSummary);
    }

    /**
     * Assert that {@code side.readChannelState(channelId)}'s status stays equal to
     * {@code expected} for the entire {@code window}. Used for "should be stuck" assertions.
     *
     * @param side          the interactor whose chain to query
     * @param channelId  the 32-byte channel id
     * @param expected      the lifecycle status that must hold throughout the window
     * @param window        how long to observe before returning successfully
     */
    public static void assertStatusStable(
            @NonNull final ContractInteractor side,
            @NonNull final byte[] channelId,
            @NonNull final ClprChannelStatus expected,
            @NonNull final Duration window) {
        final Duration pollInterval = Duration.ofMillis(500);
        final long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                final var stateOpt = side.readChannelState(channelId);
                if (stateOpt.isEmpty()) {
                    throw new AssertionError("Channel status was " + expected
                            + " but the Channel record disappeared during the " + window + " window");
                }
                final ClprChannelStatus observed =
                        ClprChannelStatus.fromProtobufOrdinal(stateOpt.get().status());
                if (observed != expected) {
                    throw new AssertionError("Channel status was " + expected + " but transitioned to " + observed
                            + " before the " + window + " stability window elapsed");
                }
            } catch (final JsonRpcException | IndexOutOfBoundsException e) {
                // Transient probe failure — don't claim instability from a momentary RPC blip.
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while asserting status stable at " + expected);
            }
        }
    }
}
