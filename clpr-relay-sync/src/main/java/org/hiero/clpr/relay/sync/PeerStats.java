// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.sync;

/**
 * Tracks success/failure statistics for a single peer, including exponential backoff state.
 *
 * <p>All methods are thread-safe via {@code synchronized}.
 */
class PeerStats {

    private int successCount = 0;
    private int failureCount = 0;
    private long backoffUntilMs = 0;

    /** Record a successful sync. Resets consecutive failure count and clears backoff. */
    synchronized void recordSuccess() {
        successCount++;
        failureCount = 0;
        backoffUntilMs = 0;
    }

    /**
     * Record a failed sync. Applies exponential backoff: 1 s, 2 s, 4 s, 8 s, … capped at 60 s.
     */
    synchronized void recordFailure() {
        failureCount++;
        long backoffMs = Math.min(1000L * (1L << (failureCount - 1)), 60_000L);
        backoffUntilMs = System.currentTimeMillis() + backoffMs;
    }

    /**
     * Returns {@code true} if the peer is currently in its backoff window and should not be
     * selected for syncing.
     *
     * @return {@code true} when the backoff period has not yet elapsed
     */
    synchronized boolean isBackedOff() {
        return System.currentTimeMillis() < backoffUntilMs;
    }

    /**
     * Returns the selection weight for this peer. New peers (no successes yet) get weight 1.0 so
     * that they are always eligible for weighted random selection.
     *
     * @return {@code successCount + 1.0}
     */
    synchronized double weight() {
        return successCount + 1.0;
    }
}
