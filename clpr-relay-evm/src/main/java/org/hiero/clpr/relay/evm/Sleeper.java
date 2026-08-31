// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

/**
 * Abstraction over {@link Thread#sleep} to allow fast-forwarding in tests.
 *
 * <p>Production code passes {@link #DEFAULT}; tests inject a no-op.
 */
@FunctionalInterface
interface Sleeper {

    /** Sleeper that delegates to the real {@link Thread#sleep}. */
    Sleeper DEFAULT = ms -> {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    };

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param millis duration in milliseconds
     * @throws InterruptedException if the thread is interrupted
     */
    void sleep(long millis) throws InterruptedException;
}
