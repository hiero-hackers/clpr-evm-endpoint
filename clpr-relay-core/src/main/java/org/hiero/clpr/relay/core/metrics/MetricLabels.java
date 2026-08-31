// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encodes and decodes Prometheus-style label pairs inside swirlds metric names.
 *
 * <p>The swirlds {@code Metrics} API has no native label concept — metrics are keyed only
 * by {@code category:name}. This utility encodes label pairs into the name string using a
 * bracket suffix so that {@link PrometheusExporter} can reconstruct proper Prometheus label
 * syntax ({@code {key="value"}}) when rendering the {@code /metrics} scrape output.
 *
 * <h2>Format</h2>
 * <pre>{@code
 * baseName[key1=value1,key2=value2]
 * }</pre>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code cycles.total[channel_id=0x1a2b3c4d]}</li>
 *   <li>{@code tx.failures[channel_id=0x1a2b,reason=rpc_error]}</li>
 * </ul>
 *
 * <h2>Constraints</h2>
 * Label keys must not be empty. Label keys and values must not contain {@code [}, {@code ]},
 * {@code ,}, or {@code =}. Label values must not be empty.
 *
 * <h2>Ordering</h2>
 * Labels are always sorted alphabetically by key before encoding, so
 * {@code labeled("m", "z", "1", "a", "2")} and {@code labeled("m", "a", "2", "z", "1")}
 * produce the same encoded string. This ensures that two {@link LabeledCounter} callers
 * that pass the same labels in different orders always resolve to the same underlying
 * {@link com.swirlds.metrics.api.Counter}.
 */
public final class MetricLabels {

    private MetricLabels() {}

    /**
     * Append a single label pair to a base metric name.
     *
     * @param baseName   the undecorated metric name (e.g. {@code "cycles.total"})
     * @param labelKey   the label key (e.g. {@code "channel_id"})
     * @param labelValue the label value (e.g. {@code "0x1a2b3c4d"})
     * @return the encoded name (e.g. {@code "cycles.total[channel_id=0x1a2b3c4d]"})
     */
    public static String labeled(final String baseName, final String labelKey, final String labelValue) {
        validateKey(labelKey);
        validateValue(labelValue);
        return baseName + '[' + labelKey + '=' + labelValue + ']';
    }

    /**
     * Append multiple label pairs to a base metric name, sorted alphabetically by key.
     *
     * <p>Labels are sorted before encoding so that callers providing the same label set
     * in different orders always produce the same encoded name (and thus resolve to the same
     * metric in the registry).
     *
     * @param baseName the undecorated metric name
     * @param labels   key-value label pairs
     * @return the encoded name
     */
    public static String labeled(final String baseName, final Map<String, String> labels) {
        if (labels.isEmpty()) {
            return baseName;
        }
        // Sort by key so label order is canonical regardless of insertion order.
        final var sorted = new TreeMap<>(labels);
        final var sb = new StringBuilder(baseName).append('[');
        boolean first = true;
        for (final var entry : sorted.entrySet()) {
            validateKey(entry.getKey());
            validateValue(entry.getValue());
            if (!first) sb.append(',');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * Parse an encoded metric name into its base name and label map.
     *
     * <p>If the name has no {@code []} suffix the returned {@link ParsedName#labels()} map
     * is empty, which is the correct representation of an unlabeled metric.
     *
     * @param name the raw metric name, possibly with a label suffix
     * @return a record holding the base name and (possibly empty) label map
     */
    public static ParsedName parse(final String name) {
        final int open = name.indexOf('[');
        if (open < 0) {
            return new ParsedName(name, Map.of());
        }
        final int close = name.lastIndexOf(']');
        if (close <= open) {
            // Malformed suffix — treat the whole string as the base name.
            return new ParsedName(name, Map.of());
        }
        final String baseName = name.substring(0, open);
        final String labelSection = name.substring(open + 1, close);
        final Map<String, String> labels = new TreeMap<>();
        for (final String pair : labelSection.split(",", -1)) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                labels.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new ParsedName(baseName, Collections.unmodifiableMap(labels));
    }

    private static void validateKey(final String key) {
        if (key.isEmpty()) throw new IllegalArgumentException("Label key must not be empty");
        checkNoReserved(key, "Label key");
    }

    private static void validateValue(final String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("Label value must not be empty");
        checkNoReserved(value, "Label value");
    }

    private static void checkNoReserved(final String s, final String field) {
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '[' || c == ']' || c == ',' || c == '=') {
                throw new IllegalArgumentException(field + " must not contain '[', ']', ',', or '='; got: " + s);
            }
        }
    }

    /**
     * A metric name split into its base name and label map.
     *
     * @param baseName the name without any label suffix
     * @param labels   immutable, alphabetically-ordered map of label key → value; empty for unlabeled metrics
     */
    public record ParsedName(String baseName, Map<String, String> labels) {}
}
