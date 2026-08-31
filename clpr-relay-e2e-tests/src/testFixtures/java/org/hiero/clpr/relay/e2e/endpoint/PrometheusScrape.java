// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.endpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One parsed snapshot of an endpoint's {@code /metrics} output.
 *
 * <p>Containerizing the endpoint costs the tests the in-process {@code Metrics} registry the
 * existing integration suite reads directly, so the Prometheus text format becomes the assertion
 * surface. That works because the relay's exporter emits real label syntax with a {@code clpr_}
 * prefix and groups {@code LabeledCounter} families properly, e.g.
 * {@code clpr_evm_tx_reverts{channel_id="0x1a2b"} 3}.
 *
 * <p>Immutable: a snapshot is a point-in-time reading. Tests that wait for a counter to move must
 * re-scrape rather than re-query the same instance, which is why {@code EvmEndpoint.metrics()}
 * returns a fresh one each call.
 */
public final class PrometheusScrape {

    /** A single time series: metric name plus its label set. */
    public record Series(String name, Map<String, String> labels) {
        public Series {
            labels = Map.copyOf(labels);
        }
    }

    private final Map<Series, Double> values;
    private final String raw;

    private PrometheusScrape(final Map<Series, Double> values, final String raw) {
        this.values = values;
        this.raw = raw;
    }

    /**
     * Parse a Prometheus text exposition.
     *
     * @param body the scrape body
     * @return the parsed snapshot
     */
    public static PrometheusScrape parse(final String body) {
        final Map<Series, Double> parsed = new LinkedHashMap<>();
        for (final String line : body.split("\\R")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // blank, HELP or TYPE
            }
            // The exposition format is `identifier value [timestamp]`. Splitting on the LAST space
            // would fold the value into the identifier as soon as a timestamp is present, silently
            // yielding the timestamp as the reading — and the metric surface is this framework's
            // primary oracle, so a wrong number is worse than a parse failure. Split on the FIRST
            // space outside the label block instead, and ignore any trailing timestamp.
            final int split = valueSeparatorIndex(trimmed);
            if (split < 0) {
                continue;
            }
            final String identifier = trimmed.substring(0, split).trim();
            final String remainder = trimmed.substring(split + 1).trim();
            final int timestampSeparator = remainder.indexOf(' ');
            final String rawValue = timestampSeparator < 0 ? remainder : remainder.substring(0, timestampSeparator);

            final double value = parseValue(rawValue);
            if (Double.isNaN(value) && !"NaN".equals(rawValue)) {
                continue; // not a sample line
            }

            final int brace = identifier.indexOf('{');
            if (brace < 0) {
                parsed.put(new Series(identifier, Map.of()), value);
            } else {
                final String name = identifier.substring(0, brace);
                final int close = identifier.lastIndexOf('}');
                final String labelBlock = close > brace ? identifier.substring(brace + 1, close) : "";
                parsed.put(new Series(name, parseLabels(labelBlock)), value);
            }
        }
        return new PrometheusScrape(parsed, body);
    }

    /**
     * Index of the space separating the identifier from its value, skipping any inside the label block.
     *
     * @return the index, or {@code -1} when the line has no value
     */
    private static int valueSeparatorIndex(final String line) {
        boolean inQuotes = false;
        boolean escaped = false;
        boolean inLabels = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inQuotes) {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && c == '{') {
                inLabels = true;
            } else if (!inQuotes && c == '}') {
                inLabels = false;
            } else if (c == ' ' && !inQuotes && !inLabels) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parse a sample value, honouring the format's {@code +Inf} / {@code -Inf} / {@code NaN} spellings.
     *
     * <p>{@link Double#parseDouble} rejects {@code +Inf}, so a bucket boundary or a saturated gauge
     * would otherwise be skipped as "not a sample line" and read back as absent.
     *
     * @return the value, or {@link Double#NaN} when the token is not a number
     */
    private static double parseValue(final String raw) {
        switch (raw) {
            case "+Inf", "Inf":
                return Double.POSITIVE_INFINITY;
            case "-Inf":
                return Double.NEGATIVE_INFINITY;
            case "NaN":
                return Double.NaN;
            default:
                try {
                    return Double.parseDouble(raw);
                } catch (final NumberFormatException e) {
                    return Double.NaN;
                }
        }
    }

    /**
     * Split a label block such as {@code channel_id="0x1a",reason="rejected"}.
     *
     * <p>Splits on commas outside quotes rather than on every comma: label <em>values</em> can
     * legitimately contain commas, and a naive split would silently produce a wrong label set — and
     * therefore a metric lookup that never matches.
     */
    private static Map<String, String> parseLabels(final String labelBlock) {
        final Map<String, String> labels = new LinkedHashMap<>();
        boolean inQuotes = false;
        final StringBuilder current = new StringBuilder();
        final List<String> pairs = new ArrayList<>();
        boolean escaped = false;
        for (int i = 0; i < labelBlock.length(); i++) {
            final char c = labelBlock.charAt(i);
            if (escaped) {
                escaped = false;
                current.append(c);
            } else if (c == '\\' && inQuotes) {
                escaped = true;
                current.append(c);
            } else if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                pairs.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            pairs.add(current.toString());
        }
        for (final String pair : pairs) {
            final int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            // The format escapes backslash, double quote and newline inside label values.
            value = value.replace("\\\\", "\u0000")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\u0000", "\\");
            labels.put(key, value);
        }
        return labels;
    }

    /**
     * Look up one series exactly.
     *
     * @param name metric name, e.g. {@code clpr_evm_tx_reverts}
     * @param labels the full label set; must match exactly
     * @return the value if present
     */
    public Optional<Double> value(final String name, final Map<String, String> labels) {
        return Optional.ofNullable(values.get(new Series(name, labels)));
    }

    /**
     * A counter's value, or zero when the series is absent.
     *
     * <p>Absent-means-zero is the right default for a relay counter: the registry creates a labeled
     * series lazily on first increment, so "no series" and "zero" are the same observation.
     *
     * @param name metric name
     * @param labels the full label set
     * @return the value, or {@code 0}
     */
    public long counter(final String name, final Map<String, String> labels) {
        return value(name, labels).map(Double::longValue).orElse(0L);
    }

    /**
     * Sum every series whose name matches exactly, across all label combinations.
     *
     * <p>Exact-name matching, so it will not fold a histogram's {@code _bucket} / {@code _sum} /
     * {@code _count} siblings together — those are separate names. Only meaningful for counters; the
     * relay emits no histograms today.
     *
     * @param name metric name
     * @return the summed value, or {@code 0} when the family is absent
     */
    public long counterSum(final String name) {
        return values.entrySet().stream()
                .filter(e -> e.getKey().name().equals(name))
                .mapToLong(e -> e.getValue().longValue())
                .sum();
    }

    /**
     * A gauge value.
     *
     * @param name metric name
     * @param labels the full label set
     * @return the value, or {@code Double.NaN} when absent — distinguishable from a real zero,
     *     which matters for gauges where zero is a meaningful reading (e.g. failing-channel counts)
     */
    public double gauge(final String name, final Map<String, String> labels) {
        return value(name, labels).orElse(Double.NaN);
    }

    /**
     * Every series whose name matches, with its labels.
     *
     * @param name metric name
     * @return matching series and values
     */
    public Map<Series, Double> family(final String name) {
        final Map<Series, Double> out = new LinkedHashMap<>();
        values.forEach((series, value) -> {
            if (series.name().equals(name)) {
                out.put(series, value);
            }
        });
        return out;
    }

    /** @return every parsed series. */
    public Map<Series, Double> all() {
        return Map.copyOf(values);
    }

    /** @return the unparsed scrape body, for diagnostics dumps. */
    public String raw() {
        return raw;
    }
}
