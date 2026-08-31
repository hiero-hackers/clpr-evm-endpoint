// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricLabelsTest {

    @Test
    void labeled_singleLabel_encodesCorrectly() {
        final String result = MetricLabels.labeled("cycles.total", "channel_id", "0x1a2b3c4d");
        assertThat(result).isEqualTo("cycles.total[channel_id=0x1a2b3c4d]");
    }

    @Test
    void labeled_multipleLabels_encodesInOrder() {
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put("channel_id", "0x1a2b");
        labels.put("reason", "rpc_error");
        final String result = MetricLabels.labeled("tx.failures", labels);
        assertThat(result).isEqualTo("tx.failures[channel_id=0x1a2b,reason=rpc_error]");
    }

    @Test
    void labeled_emptyMap_returnsBaseName() {
        assertThat(MetricLabels.labeled("cycles.total", Map.of())).isEqualTo("cycles.total");
    }

    @Test
    void labeled_rejectsReservedCharsInKey() {
        assertThatThrownBy(() -> MetricLabels.labeled("name", "bad=key", "value"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricLabels.labeled("name", "bad[key", "value"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricLabels.labeled("name", "bad,key", "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labeled_rejectsReservedCharsInValue() {
        assertThatThrownBy(() -> MetricLabels.labeled("name", "key", "bad]value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labeled_rejectsEmptyKey() {
        assertThatThrownBy(() -> MetricLabels.labeled("name", "", "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_withSingleLabel_roundTrips() {
        final String encoded = MetricLabels.labeled("cycles.total", "channel_id", "0x1a2b3c4d");
        final MetricLabels.ParsedName parsed = MetricLabels.parse(encoded);

        assertThat(parsed.baseName()).isEqualTo("cycles.total");
        assertThat(parsed.labels()).containsExactly(Map.entry("channel_id", "0x1a2b3c4d"));
    }

    @Test
    void parse_withMultipleLabels_roundTrips() {
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put("channel_id", "0x1a2b");
        labels.put("reason", "rpc_error");
        final String encoded = MetricLabels.labeled("tx.failures", labels);
        final MetricLabels.ParsedName parsed = MetricLabels.parse(encoded);

        assertThat(parsed.baseName()).isEqualTo("tx.failures");
        assertThat(parsed.labels())
                .containsExactly(Map.entry("channel_id", "0x1a2b"), Map.entry("reason", "rpc_error"));
    }

    @Test
    void parse_noLabelSuffix_returnsEmptyLabels() {
        final MetricLabels.ParsedName parsed = MetricLabels.parse("cycles.total");
        assertThat(parsed.baseName()).isEqualTo("cycles.total");
        assertThat(parsed.labels()).isEmpty();
    }

    @Test
    void parse_malformedSuffix_treatsWholeNameAsBase() {
        final MetricLabels.ParsedName parsed = MetricLabels.parse("cycles.total[broken");
        assertThat(parsed.baseName()).isEqualTo("cycles.total[broken");
        assertThat(parsed.labels()).isEmpty();
    }
}
