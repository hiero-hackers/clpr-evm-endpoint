// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongGauge;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class PrometheusExporterTest {

    @Test
    void rendersCounterWithHelpAndType() {
        final var metrics = new SimpleMetrics();
        final var counter =
                metrics.getOrCreate(new Counter.Config("sync", "cycles.total").withDescription("Total sync cycles"));
        counter.add(5);

        final String output = PrometheusExporter.render(metrics);

        assertThat(output).contains("# HELP clpr_sync_cycles_total Total sync cycles");
        assertThat(output).contains("# TYPE clpr_sync_cycles_total counter");
        assertThat(output).contains("clpr_sync_cycles_total 5");
    }

    @Test
    void rendersLongGaugeAsGauge() {
        final var metrics = new SimpleMetrics();
        final var gauge =
                metrics.getOrCreate(new LongGauge.Config("sync", "active.tasks").withDescription("Live sync loops"));
        gauge.set(3);

        final String output = PrometheusExporter.render(metrics);

        assertThat(output).contains("# TYPE clpr_sync_active_tasks gauge");
        assertThat(output).contains("clpr_sync_active_tasks 3");
    }

    @Test
    void metricsSortedByCategoryThenName() {
        final var metrics = new SimpleMetrics();
        metrics.getOrCreate(new Counter.Config("sync", "b"));
        metrics.getOrCreate(new Counter.Config("evm", "a"));
        metrics.getOrCreate(new Counter.Config("sync", "a"));

        final String output = PrometheusExporter.render(metrics);

        final int evmA = output.indexOf("clpr_evm_a");
        final int syncA = output.indexOf("clpr_sync_a");
        final int syncB = output.indexOf("clpr_sync_b");
        assertThat(evmA).isLessThan(syncA);
        assertThat(syncA).isLessThan(syncB);
    }

    @Test
    void labeledCounters_groupedUnderSingleHelpAndTypeBlock() {
        final var metrics = new SimpleMetrics();
        final var c1 = metrics.getOrCreate(
                new Counter.Config("sync", MetricLabels.labeled("cycles.total", "channel_id", "conn0"))
                        .withDescription("Total sync cycles"));
        final var c2 = metrics.getOrCreate(
                new Counter.Config("sync", MetricLabels.labeled("cycles.total", "channel_id", "conn1"))
                        .withDescription("Total sync cycles"));
        c1.add(10);
        c2.add(20);

        final String output = PrometheusExporter.render(metrics);

        // Only one HELP and one TYPE block for the family.
        assertThat(countOccurrences(output, "# HELP clpr_sync_cycles_total")).isEqualTo(1);
        assertThat(countOccurrences(output, "# TYPE clpr_sync_cycles_total")).isEqualTo(1);

        // Both label variants appear as separate value lines.
        assertThat(output).contains("clpr_sync_cycles_total{channel_id=\"conn0\"} 10");
        assertThat(output).contains("clpr_sync_cycles_total{channel_id=\"conn1\"} 20");
    }

    @Test
    void unlabeledAndLabeledMetrics_renderedCorrectly() {
        final var metrics = new SimpleMetrics();
        final var plain = metrics.getOrCreate(new Counter.Config("evm", "tx.failures").withDescription("Failures"));
        final var labeled = metrics.getOrCreate(
                new Counter.Config("evm", MetricLabels.labeled("tx.submissions", "channel_id", "conn0"))
                        .withDescription("Submissions"));
        plain.add(3);
        labeled.add(7);

        final String output = PrometheusExporter.render(metrics);

        assertThat(output).contains("clpr_evm_tx_failures 3");
        assertThat(output).contains("clpr_evm_tx_submissions{channel_id=\"conn0\"} 7");
    }

    @Test
    void labeledCounter_labelValueEscapedInOutput() {
        final var metrics = new SimpleMetrics();
        // Label value containing a double-quote (edge case).
        final var c = metrics.getOrCreate(
                new Counter.Config("evm", MetricLabels.labeled("tx.submissions", "peer", "host:9545")));
        c.add(1);

        final String output = PrometheusExporter.render(metrics);

        assertThat(output).contains("clpr_evm_tx_submissions{peer=\"host:9545\"} 1");
    }

    @Test
    void labeledCounterHelper_incrementsCorrectFamilyMembers() {
        final var metrics = new SimpleMetrics();
        final var family = new LabeledCounter("sync", "bundle.submissions", "Peer bundles submitted", metrics);

        family.increment("channel_id", "conn0");
        family.increment("channel_id", "conn0");
        family.increment("channel_id", "conn1");

        final String output = PrometheusExporter.render(metrics);

        assertThat(output).contains("clpr_sync_bundle_submissions{channel_id=\"conn0\"} 2");
        assertThat(output).contains("clpr_sync_bundle_submissions{channel_id=\"conn1\"} 1");
        assertThat(countOccurrences(output, "# TYPE clpr_sync_bundle_submissions"))
                .isEqualTo(1);
    }

    @Test
    void labeledCounterHelper_supportsMultipleLabels() {
        final var metrics = new SimpleMetrics();
        final var family = new LabeledCounter("evm", "tx.failures", "Tx failures", metrics);

        family.increment("channel_id", "conn0", "reason", "rpc_error");
        family.increment("channel_id", "conn0", "reason", "rpc_error");
        family.increment("channel_id", "conn0", "reason", "preverify");

        final String output = PrometheusExporter.render(metrics);

        assertThat(output).contains("clpr_evm_tx_failures{channel_id=\"conn0\",reason=\"rpc_error\"} 2");
        assertThat(output).contains("clpr_evm_tx_failures{channel_id=\"conn0\",reason=\"preverify\"} 1");
        assertThat(countOccurrences(output, "# TYPE clpr_evm_tx_failures")).isEqualTo(1);
    }

    @Test
    void labeledCounterHelper_rejectsOddLengthPairs() {
        final var metrics = new SimpleMetrics();
        final var family = new LabeledCounter("evm", "tx.failures", "Tx failures", metrics);

        Assertions.assertThatThrownBy(() -> family.increment("channel_id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int countOccurrences(final String text, final String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
