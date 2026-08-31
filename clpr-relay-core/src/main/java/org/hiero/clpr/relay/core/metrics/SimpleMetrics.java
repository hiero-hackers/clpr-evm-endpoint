// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.metrics;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.metrics.api.MetricsFactory;
import com.swirlds.metrics.impl.DefaultMetricsFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Minimal {@link Metrics} registry backing the CLPR relay.
 *
 * <p>Backed by a {@link ConcurrentHashMap} of metrics keyed by {@code category:name}. This
 * deliberately avoids {@code swirlds-common}'s {@code DefaultMetrics} so we do not pull in
 * the full platform dependency graph (Bouncy Castle, Besu native libs, Prometheus servlet,
 * etc.) for the handful of metrics the relay actually emits.
 */
public final class SimpleMetrics implements Metrics {

    private static final Logger LOGGER = Loggers.getLogger(SimpleMetrics.class);

    private final MetricsFactory factory = new DefaultMetricsFactory();
    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
    private final List<Runnable> updaters = new CopyOnWriteArrayList<>();

    @Nullable
    @Override
    public Metric getMetric(@NonNull final String category, @NonNull final String name) {
        return metrics.get(key(category, name));
    }

    @NonNull
    @Override
    public Collection<Metric> findMetricsByCategory(@NonNull final String category) {
        final String prefix = category + ":";
        final List<Metric> out = new ArrayList<>();
        for (final var entry : metrics.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                out.add(entry.getValue());
            }
        }
        return out;
    }

    @NonNull
    @Override
    public Collection<Metric> getAll() {
        return List.copyOf(metrics.values());
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Metric> T getOrCreate(@NonNull final MetricConfig<T, ?> config) {
        final String k = key(config.getCategory(), config.getName());
        final Metric existing = metrics.get(k);
        if (existing != null) {
            return (T) checkCompatible(k, existing, config);
        }
        final T created = config.create(factory);
        final Metric prior = metrics.putIfAbsent(k, created);
        return prior != null ? (T) checkCompatible(k, prior, config) : created;
    }

    /**
     * Verify that an already-registered metric is compatible with the requested config so that
     * the unchecked cast in {@link #getOrCreate(MetricConfig)} is safe. Throws fast at the call
     * site that triggered the type mismatch instead of producing a {@link ClassCastException}
     * later at an unrelated read.
     */
    @NonNull
    private static Metric checkCompatible(
            @NonNull final String key, @NonNull final Metric existing, @NonNull final MetricConfig<?, ?> requested) {
        if (!requested.getResultClass().isInstance(existing)) {
            throw new IllegalStateException("Metric already registered for key '" + key
                    + "' with incompatible type. Expected "
                    + requested.getResultClass().getName()
                    + " but found " + existing.getClass().getName());
        }
        return existing;
    }

    @Override
    public void remove(@NonNull final String category, @NonNull final String name) {
        metrics.remove(key(category, name));
    }

    @Override
    public void remove(@NonNull final Metric metric) {
        metrics.remove(key(metric.getCategory(), metric.getName()));
    }

    @Override
    public void remove(@NonNull final MetricConfig<?, ?> config) {
        metrics.remove(key(config.getCategory(), config.getName()));
    }

    @Override
    public void addUpdater(@NonNull final Runnable updater) {
        updaters.add(updater);
    }

    @Override
    public void removeUpdater(@NonNull final Runnable updater) {
        updaters.remove(updater);
    }

    @Override
    public void start() {
        // No-op: metrics update on demand when /metrics is scraped.
    }

    /** Run every registered updater. Called from the Prometheus exporter before serializing. */
    public void runUpdaters() {
        for (final var updater : updaters) {
            updater.run();
        }
    }

    /**
     * Run every registered updater, catching exceptions per-updater so that a single misbehaving
     * updater cannot fail an entire {@code /metrics} scrape.
     */
    public void runUpdatersSafely() {
        for (final var updater : updaters) {
            try {
                updater.run();
            } catch (final RuntimeException e) {
                LOGGER.warn("Metrics updater threw {}: {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @NonNull
    private static String key(@NonNull final String category, @NonNull final String name) {
        return category + ":" + name;
    }
}
