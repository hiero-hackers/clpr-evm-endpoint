// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PeerManifestVersionCache} — the per-channel record of the manifest version
 * each peer reports holding of this endpoint's manifest, which gates the outbound manifest-proof
 * attach decision in {@link ManifestProofPolicy}.
 */
class PeerManifestVersionCacheTest {

    private static final Bytes CONN_A = Bytes.wrap(new byte[32]);
    private static final Bytes CONN_B = Bytes.fromHex("11".repeat(32));

    private PeerManifestVersionCache cache;

    @BeforeEach
    void setUp() {
        cache = new PeerManifestVersionCache();
    }

    @Test
    void unknownChannelDefaultsToZero() {
        assertThat(cache.knownVersion(CONN_A)).isZero();
    }

    @Test
    void recordThenReadReturnsRecordedVersion() {
        cache.record(CONN_A, 7L);
        assertThat(cache.knownVersion(CONN_A)).isEqualTo(7L);
    }

    @Test
    void recordIsMonotonic_lowerVersionIsIgnored() {
        cache.record(CONN_A, 5L);
        cache.record(CONN_A, 3L); // out-of-order / stale inbound sync must not lower the known version
        assertThat(cache.knownVersion(CONN_A)).isEqualTo(5L);
    }

    @Test
    void recordAdvancesToHigherVersion() {
        cache.record(CONN_A, 2L);
        cache.record(CONN_A, 9L);
        assertThat(cache.knownVersion(CONN_A)).isEqualTo(9L);
    }

    @Test
    void versionsAreTrackedPerChannel() {
        cache.record(CONN_A, 4L);
        cache.record(CONN_B, 8L);
        assertThat(cache.knownVersion(CONN_A)).isEqualTo(4L);
        assertThat(cache.knownVersion(CONN_B)).isEqualTo(8L);
    }
}
