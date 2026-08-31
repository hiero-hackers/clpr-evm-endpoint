// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import java.time.Duration;
import java.util.Map;
import org.hiero.clpr.relay.e2e.E2EEnvironment;
import org.hiero.clpr.relay.e2e.E2ETest;
import org.hiero.clpr.relay.e2e.chain.BesuNetwork;
import org.hiero.clpr.relay.e2e.chain.BesuNetworkSpec;
import org.hiero.clpr.relay.e2e.channel.Channel;
import org.hiero.clpr.relay.e2e.channel.ChannelSpec;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpoint;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpointSpec;

/**
 * The gate for the whole E2E path: a channel between two independent Besu QBFT chains, completed
 * against the <b>real</b> {@code QBFTVerifier}, served by two containerized relays.
 *
 * <p>Everything above this test depends on the claim it makes. The existing integration suite avoids
 * the real verifier by deploying {@code StubClprVerifier} and injecting a re-encoding submitter into
 * an in-process relay; a container has no such seam, so the production verification path — QBFT
 * committed seals, Merkle account and storage proofs, code-hash and trust-anchor checks — has to be
 * satisfied for real. This test proves the two config proofs are byte-correct and that both relays
 * bring the channel up from it.
 */
class SingleChannelSmokeE2ETest {

    @E2ETest(timeoutSeconds = 900)
    void channel_comes_up_between_two_chains_with_the_real_verifier(final E2EEnvironment env) {
        final BesuNetwork besuA = env.besuNetworks().add(BesuNetworkSpec.singleNode());
        final BesuNetwork besuB = env.besuNetworks().add(BesuNetworkSpec.singleNode());

        final EvmEndpoint epA = env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-a"));
        final EvmEndpoint epB = env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-b"));

        final Channel channel =
                env.channels().create(ChannelSpec.between(besuA, besuB).endpoints(epA, epB));

        // Chains, contracts, channel wiring, endpoints — in that order. Reaching the end of this call
        // already means completeChannel was accepted on both chains, which is the real verifier
        // signing off on the config proofs.
        env.startAll();

        assertThat(channel.idHex()).hasSize(66).startsWith("0x");

        // The same 32-byte id identifies the channel on both ledgers, and both records are ACTIVE.
        // (state() throws when the record is absent, so asserting non-null would prove nothing.)
        assertThat(channel.a().status()).isEqualTo(ClprChannelStatus.ACTIVE);
        assertThat(channel.b().status()).isEqualTo(ClprChannelStatus.ACTIVE);

        // Both sides start empty: nothing sent, nothing to acknowledge.
        assertThat(channel.a().queueDepth()).isZero();
        assertThat(channel.b().queueDepth()).isZero();

        // Each side has a connector holding its prepaid execution budget. Without it the service
        // answers inbound messages with CONNECTOR_UNDERFUNDED instead of dispatching them.
        assertThat(channel.a().connector().balance())
                .as("side A connector is funded")
                .isEqualTo(ChannelSpec.DEFAULT_CONNECTOR_FUNDING_WEI);
        assertThat(channel.b().connector().balance())
                .as("side B connector is funded")
                .isEqualTo(ChannelSpec.DEFAULT_CONNECTOR_FUNDING_WEI);

        // The endpoints must have actually brought the channel up, not merely started. A channel whose
        // on-chain protocol version mismatches is skipped silently, so "the container is healthy" is
        // not evidence — a sync cycle attributed to this channel id is.
        for (final EvmEndpoint endpoint : new EvmEndpoint[] {epA, epB}) {
            // env.await() rather than a raw poll: it aborts early on a fatal marker in the relay logs
            // and names the last observed state on timeout.
            env.await().channelLive(endpoint, channel.metricLabel(), Duration.ofMinutes(3));
        }

        // No channel may be reporting failures once it is up.
        for (final EvmEndpoint endpoint : new EvmEndpoint[] {epA, epB}) {
            assertThat(endpoint.metrics().gauge("clpr_sync_channels_failing", Map.of()))
                    .as(endpoint.id() + " reports no failing channels")
                    .isEqualTo(0.0);
        }

        // Nothing in the whole bring-up may have reverted. Contract-level failures are otherwise easy
        // to miss: a reverted transaction is mined and charged just like a successful one.
        for (final BesuNetwork network : env.besuNetworks().all()) {
            assertThat(network.inspect()
                            .revertedTransactions(0, network.inspect().headBlockNumber()))
                    .as("no reverted transactions on " + network.id())
                    .isEmpty();
        }

        assertThat(epA.logs().errorLines()).as("relay A logged no errors").isEmpty();
        assertThat(epB.logs().errorLines()).as("relay B logged no errors").isEmpty();
    }
}
