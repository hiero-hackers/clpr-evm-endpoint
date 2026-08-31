// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.util.function.LongPredicate;

/**
 * Assertions over an endpoint's operational counters — the surface that proves which internal path the
 * endpoint took (deduped / reverted / shunned / …). Because the relay's counters are
 * {@code LabeledCounter} families encoded as {@code baseName[channel_id=…]} under a category, this
 * sums every series sharing the base name.
 */
public final class MetricAssert {

    private static final long POLL_INTERVAL_MS = 50L;

    private final Metrics metrics;
    private final String category;
    private final String baseName;
    private Duration timeout = Duration.ZERO;

    MetricAssert(final Metrics metrics, final String category, final String baseName) {
        this.metrics = metrics;
        this.category = category;
        this.baseName = baseName;
    }

    /** Poll for up to {@code timeout} until the asserted condition holds (default: assert immediately). */
    public MetricAssert eventually(final Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /** The summed value across all label series sharing the base name. */
    public long value() {
        return metrics.findMetricsByCategory(category).stream()
                .filter(m -> m.getName().startsWith(baseName))
                .filter(Counter.class::isInstance)
                .mapToLong(m -> ((Counter) m).get())
                .sum();
    }

    public MetricAssert isEqualTo(final long expected) {
        await(v -> v == expected);
        return require(value() == expected, "to be <" + expected + ">");
    }

    public MetricAssert isZero() {
        return isEqualTo(0L);
    }

    public MetricAssert isPositive() {
        await(v -> v > 0L);
        return require(value() > 0L, "to be positive");
    }

    public MetricAssert isGreaterThan(final long other) {
        await(v -> v > other);
        return require(value() > other, "to be greater than <" + other + ">");
    }

    private MetricAssert require(final boolean ok, final String expectation) {
        if (!ok) {
            throw new AssertionError(
                    "Expected metric " + category + '.' + baseName + ' ' + expectation + " but was <" + value() + ">");
        }
        return this;
    }

    private void await(final LongPredicate condition) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.test(value()) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
