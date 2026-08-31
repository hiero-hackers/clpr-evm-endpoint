// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.metrics;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lazily-populated family of {@link Counter} metrics sharing a base name but varying by one
 * or more label values.
 *
 * <p>Use this when label values (e.g. a channel ID, a peer address, a failure reason) are
 * not known at construction time but only at call time. Each unique combination of label
 * key-value pairs gets its own {@link Counter} instance registered in the shared {@link Metrics}
 * registry under a name encoded by {@link MetricLabels}. The {@link PrometheusExporter} groups
 * these into a single metric family on scrape.
 *
 * <h2>Example — single label</h2>
 * <pre>{@code
 * var submissions = new LabeledCounter("evm", "tx.submissions",
 *         "Bundle transactions submitted", metrics);
 *
 * submissions.increment("channel_id", connIdHex);
 * }</pre>
 *
 * <h2>Example — two labels</h2>
 * <pre>{@code
 * var failures = new LabeledCounter("evm", "tx.failures",
 *         "Bundle submission failures", metrics);
 *
 * failures.increment("channel_id", connIdHex, "reason", "rpc_error");
 * }</pre>
 */
public final class LabeledCounter {

    private final String category;
    private final String baseName;
    private final String description;
    private final Metrics metrics;

    /**
     * Keyed by the full encoded label string (e.g. {@code "channel_id=0x1a2b,reason=rpc_error"})
     * so that any label combination maps to exactly one {@link Counter}.
     */
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * Create a labeled counter family.
     *
     * @param category    the swirlds metric category (e.g. {@code "evm"})
     * @param baseName    the base metric name without any label suffix (e.g. {@code "tx.submissions"})
     * @param description human-readable description emitted in {@code # HELP} lines
     * @param metrics     the registry to register child counters into
     */
    public LabeledCounter(
            final String category, final String baseName, final String description, final Metrics metrics) {
        this.category = category;
        this.baseName = baseName;
        this.description = description;
        this.metrics = metrics;
    }

    /**
     * Increment the counter for the given label combination by one.
     *
     * <p>{@code keyValuePairs} must be an even-length sequence of alternating label keys and
     * values: {@code "channel_id", connId, "reason", "rpc_error", ...}.
     * The counter is registered in the registry the first time a particular combination is seen.
     *
     * @param keyValuePairs alternating label key, label value pairs; must be even in length
     * @throws IllegalArgumentException if {@code keyValuePairs} has an odd length or is empty
     */
    public void increment(final String... keyValuePairs) {
        counter(keyValuePairs).increment();
    }

    /**
     * Increment the counter for the given label combination by {@code amount}.
     *
     * <p>If {@code amount} is zero the call is a no-op; the underlying swirlds {@link Counter}
     * rejects non-positive increments with an exception, so we skip them here to avoid
     * propagating errors for semantically harmless zero observations.
     *
     * @param amount        the amount to add; zero is silently ignored, negative is rejected
     * @param keyValuePairs alternating label key, label value pairs; must be even in length
     * @throws IllegalArgumentException if {@code keyValuePairs} has an odd length or is empty,
     *                                  or if {@code amount} is negative
     */
    public void add(final long amount, final String... keyValuePairs) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative, got " + amount);
        }
        if (amount > 0) {
            counter(keyValuePairs).add(amount);
        }
    }

    /**
     * Return the underlying {@link Counter} for the given label combination, creating and
     * registering it in the shared registry if this is the first call for that combination.
     *
     * @param keyValuePairs alternating label key, label value pairs; must be even in length
     * @throws IllegalArgumentException if {@code keyValuePairs} has an odd length or is empty
     */
    public Counter counter(final String... keyValuePairs) {
        if (keyValuePairs.length == 0 || keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "keyValuePairs must be a non-empty, even-length sequence of key-value pairs; got length "
                            + keyValuePairs.length);
        }
        final Map<String, String> labels = new LinkedHashMap<>(keyValuePairs.length / 2);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            labels.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        final String encodedName = MetricLabels.labeled(baseName, labels);
        return counters.computeIfAbsent(
                encodedName, n -> metrics.getOrCreate(new Counter.Config(category, n).withDescription(description)));
    }
}
