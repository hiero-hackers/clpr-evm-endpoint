// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.metrics;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricType;
import com.swirlds.metrics.api.Metrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link Metrics} registry as the Prometheus text exposition format
 * (version 0.0.4) used by {@code /metrics} scrape endpoints.
 *
 * <p>This is a deliberately minimal implementation: it handles counter, gauge, and
 * accumulator metrics (the only kinds the relay emits) and converts dotted names
 * ({@code sync.cycles.total}) to Prometheus-legal identifiers
 * ({@code clpr_sync_cycles_total}).
 *
 * <h2>Label support</h2>
 * <p>Metrics whose names were encoded by {@link MetricLabels} are rendered as a single
 * metric family with Prometheus label syntax. For example, the registry entries:
 * <pre>{@code
 *   sync:cycles.total[channel_id=0x1a2b3c4d]  → 42
 *   sync:cycles.total[channel_id=0x5e6f7a8b]  → 17
 * }</pre>
 * are emitted as:
 * <pre>{@code
 *   # HELP clpr_sync_cycles_total Total sync cycles attempted
 *   # TYPE clpr_sync_cycles_total counter
 *   clpr_sync_cycles_total{channel_id="0x1a2b3c4d"} 42
 *   clpr_sync_cycles_total{channel_id="0x5e6f7a8b"} 17
 * }</pre>
 */
public final class PrometheusExporter {

    private static final Logger LOGGER = Loggers.getLogger(PrometheusExporter.class);

    /** MIME type for Prometheus text format 0.0.4, UTF-8 encoded. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final String NAMESPACE = "clpr";

    private PrometheusExporter() {}

    /**
     * Render all metrics in the given registry as a Prometheus text exposition.
     *
     * <p>If {@code metrics} is a {@link SimpleMetrics}, any registered updaters are
     * invoked before serialization so gauge snapshots reflect the current state.
     *
     * <p>Metrics encoded with {@link MetricLabels} are grouped into a single metric
     * family with proper Prometheus label syntax. Unlabeled metrics are rendered as
     * before with one {@code # HELP} / {@code # TYPE} / value block each.
     */
    public static String render(final Metrics metrics) {
        if (metrics instanceof SimpleMetrics simple) {
            // Updaters are user-provided callbacks; a single misbehaving updater shouldn't 500
            // the whole /metrics endpoint and silence every other gauge in the registry.
            simple.runUpdatersSafely();
        }

        // Sort by (category, baseName, raw name) so all members of a labeled family are
        // adjacent, then iterate once to emit HELP/TYPE once per family.
        final List<Metric> sorted = new ArrayList<>(metrics.getAll());
        sorted.sort(Comparator.comparing(Metric::getCategory)
                .thenComparing(m -> MetricLabels.parse(m.getName()).baseName())
                .thenComparing(Metric::getName));

        final StringBuilder out = new StringBuilder(4096);
        String lastPromName = null;

        for (final Metric metric : sorted) {
            try {
                final MetricLabels.ParsedName parsed = MetricLabels.parse(metric.getName());
                final String promType = toPrometheusType(metric.getMetricType());
                if (promType == null) {
                    continue; // unsupported type — skip
                }

                final String promName = toPrometheusName(metric.getCategory(), parsed.baseName());

                // Emit HELP and TYPE only once per metric family (i.e. when the Prometheus
                // metric name changes). Within a family the description of the first member
                // is used; subsequent members' descriptions are silently ignored since
                // Prometheus requires a single HELP line per family.
                if (!promName.equals(lastPromName)) {
                    final String description = metric.getDescription();
                    if (description != null && !description.isBlank()) {
                        out.append("# HELP ")
                                .append(promName)
                                .append(' ')
                                .append(escapeHelp(description))
                                .append('\n');
                    }
                    out.append("# TYPE ")
                            .append(promName)
                            .append(' ')
                            .append(promType)
                            .append('\n');
                    lastPromName = promName;
                }

                // Emit the value line, appending a label set when labels are present.
                final Object value = metric.get(Metric.ValueType.VALUE);
                out.append(promName);
                appendLabelSet(out, parsed.labels());
                out.append(' ').append(formatValue(value)).append('\n');

            } catch (final RuntimeException e) {
                LOGGER.warn(
                        "Failed to render metric {}:{} — {}", metric.getCategory(), metric.getName(), e.getMessage());
            }
        }
        return out.toString();
    }

    /**
     * Append a Prometheus label set to {@code out} if {@code labels} is non-empty.
     * Format: {@code {key1="value1",key2="value2"}}. Label values are escaped per the
     * Prometheus text format spec (backslash, double-quote, and newline).
     */
    private static void appendLabelSet(final StringBuilder out, final Map<String, String> labels) {
        if (labels.isEmpty()) {
            return;
        }
        out.append('{');
        boolean first = true;
        for (final var entry : labels.entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append(entry.getKey())
                    .append("=\"")
                    .append(escapeLabelValue(entry.getValue()))
                    .append('"');
            first = false;
        }
        out.append('}');
    }

    private static String toPrometheusName(final String category, final String baseName) {
        final String raw = NAMESPACE + "_" + category + "_" + baseName;
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            final boolean legal =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            sb.append(legal ? c : '_');
        }
        return sb.toString();
    }

    @Nullable
    private static String toPrometheusType(final MetricType type) {
        return switch (type) {
            case COUNTER -> "counter";
            case GAUGE, ACCUMULATOR -> "gauge";
            default -> null;
        };
    }

    private static String formatValue(@Nullable final Object value) {
        if (value == null) {
            return "NaN";
        }
        if (value instanceof Double d) {
            if (Double.isNaN(d)) return "NaN";
            if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
            return Double.toString(d);
        }
        return value.toString();
    }

    private static String escapeHelp(final String s) {
        // Escape backslashes and newlines per the Prometheus text format spec.
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String escapeLabelValue(final String s) {
        // Escape backslashes, double-quotes, and newlines per the Prometheus text format spec.
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
