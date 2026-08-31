// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.hiero.clpr.relay.test.harness.RelayAssertions.assertMetric;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The on-chain peer endpoint manifest is refreshed into the listener's cache at startup, and ordinary
 * queue traffic (which does not advance {@code endpointManifestVersion}) does NOT trigger a refresh.
 * Observed through the {@code evm.listener.manifest.refreshed} counter.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class PeerManifestRefreshStubPeerTest extends OneSidedStubPeerTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void manifestRefreshedFromChainAtStartup() {
        // The listener's first observation refreshes the cache from the on-ledger manifest; the counter
        // advancing proves the decode → detect → replace path ran end to end.
        assertMetric(relay.metrics(), "evm.listener", "manifest.refreshed")
                .eventually(TIMEOUT)
                .isPositive();
    }

    @Test
    void queueTrafficDoesNotTriggerManifestRefresh() throws Exception {
        // Wait out the one-time startup refresh, capture the baseline, then exchange traffic: the queue
        // advances but endpointManifestVersion does not, so the manifest-refresh branch must not fire.
        assertMetric(relay.metrics(), "evm.listener", "manifest.refreshed")
                .eventually(TIMEOUT)
                .isPositive();
        final long baseline = assertMetric(relay.metrics(), "evm.listener", "manifest.refreshed")
                .value();

        interactor.sendMessage(channelId, connectorId, targetApp(), "x".getBytes());
        Thread.sleep(3_000); // let the listener poll several times

        assertMetric(relay.metrics(), "evm.listener", "manifest.refreshed").isEqualTo(baseline);
    }
}
