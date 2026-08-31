// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import java.util.HashSet;
import java.util.Set;

/**
 * Failure-state machine for a repeatedly-executed operation: a two-state ({@code HEALTHY} ↔
 * {@code FAILING}) model that turns a sequence of failures and successes into a backoff duration and
 * a log decision, and resets on the first clean run.
 *
 * <p>Performs no IO — it neither logs nor sleeps, leaving both to the caller — and holds no clock:
 * backoff is driven by the consecutive-failure streak, logging by an exception fingerprint. All
 * methods are thread-safe.
 */
public final class FailState {

    /**
     * The decision returned for a single failure.
     *
     * @param backoffMs how long the caller should sleep before retrying
     * @param logFullDetail {@code true} on the first occurrence of this fingerprint in the current
     *     failure episode (caller should log the full stacktrace); {@code false} on a repeat
     * @param consecutiveFailures the running episode-wide consecutive-failure count (≥ 1)
     * @param fingerprint a short hex hash identifying this throwable shape, stable across repeats so
     *     a compact repeat line can be grepped back to its first full line
     */
    public record FailureDecision(long backoffMs, boolean logFullDetail, int consecutiveFailures, String fingerprint) {}

    /** Number of top stack frames folded into a fingerprint. */
    private static final int FINGERPRINT_FRAMES = 8;

    /** Cap on the backoff exponent so {@code baseMs << shift} never overflows. */
    private static final int MAX_SHIFT = 30;

    private final long baseMs;
    private final long capMs;

    // -- mutable state, guarded by this --

    private int consecutiveFailures = 0;
    /** Fingerprints already full-logged in the current episode; cleared on success. */
    private final Set<String> seenFingerprints = new HashSet<>();

    /**
     * Create a state machine.
     *
     * @param baseMs    first-failure backoff in ms (doubled per consecutive failure)
     * @param capMs     ceiling on the per-failure backoff in ms
     */
    public FailState(final long baseMs, final long capMs) {
        if (baseMs <= 0) {
            throw new IllegalArgumentException("baseMs must be positive: " + baseMs);
        }
        if (capMs < baseMs) {
            throw new IllegalArgumentException("capMs (" + capMs + ") must be >= baseMs (" + baseMs + ")");
        }
        this.baseMs = baseMs;
        this.capMs = capMs;
    }

    /**
     * Record a failed iteration.
     *
     * @param t the throwable that ended the iteration
     * @return the backoff to wait, the streak, the fingerprint, and whether to log this fingerprint
     *     in full (its first occurrence this episode) or compact (a repeat)
     */
    public synchronized FailureDecision onFailure(final Throwable t) {
        consecutiveFailures++;
        final String fingerprint = fingerprint(t);
        final boolean firstOccurrence = seenFingerprints.add(fingerprint);
        return new FailureDecision(computeBackoffMs(), firstOccurrence, consecutiveFailures, fingerprint);
    }

    /**
     * Record a successful iteration. The first success of an episode clears all failure state and
     * returns to {@code HEALTHY}.
     *
     * @return the consecutive-failure streak just cleared (0 if already healthy), so the caller can
     *     emit a single recovery log line
     */
    public synchronized int onSuccess() {
        final int cleared = consecutiveFailures;
        if (cleared > 0) {
            consecutiveFailures = 0;
            seenFingerprints.clear();
        }
        return cleared;
    }

    /**
     * Returns the current episode-wide consecutive-failure count.
     *
     * @return the streak (0 when healthy)
     */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Returns whether the loop is currently in a failing (non-zero streak) state.
     *
     * @return {@code true} when {@link #consecutiveFailures()} {@code > 0}
     */
    public synchronized boolean isFailing() {
        return consecutiveFailures > 0;
    }

    /** Backoff for the current streak: {@code min(baseMs << (streak - 1), capMs)}. Overflow-safe. */
    private long computeBackoffMs() {
        final int shift = Math.clamp(consecutiveFailures - 1, 0, MAX_SHIFT);
        final long factor = 1L << shift;
        // Guard against base * factor overflowing long before clamping to the cap.
        if (baseMs > capMs / factor) {
            return capMs;
        }
        return Math.min(baseMs * factor, capMs);
    }

    /**
     * Compute a short, stable hex fingerprint over the throwable's class, its cause-chain classes,
     * and the top {@value #FINGERPRINT_FRAMES} frames of its stack. Deterministic for a given
     * throwable shape (no identity hashes), so the same fault produces the same fingerprint on every
     * repeat and the compact repeat line can be grepped back to its first full line.
     */
    private static String fingerprint(final Throwable t) {
        final StringBuilder sb = new StringBuilder(256);
        Throwable cursor = t;
        int causeGuard = 0;
        while (cursor != null && causeGuard < 16) {
            sb.append(cursor.getClass().getName()).append('|');
            cursor = cursor.getCause();
            causeGuard++;
        }
        final StackTraceElement[] frames = t.getStackTrace();
        final int limit = Math.min(frames.length, FINGERPRINT_FRAMES);
        for (int i = 0; i < limit; i++) {
            final StackTraceElement f = frames[i];
            sb.append(f.getClassName())
                    .append('#')
                    .append(f.getMethodName())
                    .append(':')
                    .append(f.getLineNumber())
                    .append('|');
        }
        // String.hashCode is content-stable across JVMs; 8 hex chars is plenty to disambiguate the
        // handful of fault shapes a single channel produces while staying greppable.
        return String.format("%08x", sb.toString().hashCode());
    }
}
