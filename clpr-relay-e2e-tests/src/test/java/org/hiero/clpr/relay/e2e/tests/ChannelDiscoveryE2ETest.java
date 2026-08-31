// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.hiero.clpr.relay.e2e.E2EEnvironment;
import org.hiero.clpr.relay.e2e.E2ETest;
import org.hiero.clpr.relay.e2e.chain.BesuNetwork;
import org.hiero.clpr.relay.e2e.chain.BesuNetworkSpec;
import org.hiero.clpr.relay.e2e.channel.Channel;
import org.hiero.clpr.relay.e2e.channel.ChannelSpec;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpoint;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpointSpec;
import org.hiero.clpr.relay.e2e.load.InjectionRun;
import org.hiero.clpr.relay.e2e.load.MessageShape;

/**
 * On-chain channel discovery: a running relay picking up a channel that did not exist when it
 * started.
 *
 * <p>This is the only way a live endpoint can take on new work. The relay reads its configuration
 * once at startup, so a channel wired afterwards is invisible to an endpoint with
 * {@code predefinedChannels} alone — it has to poll the service for {@code ChannelCompleted} events,
 * which is what {@code discoverChannels} turns on.
 *
 * <p>Worth testing on its own because the failure mode is silent: without discovery the channel exists
 * on chain, both relays stay healthy, and nothing is ever delivered.
 */
class ChannelDiscoveryE2ETest {

    @E2ETest(timeoutSeconds = 900)
    void a_running_endpoint_discovers_a_channel_wired_after_startup(final E2EEnvironment env) {
        final BesuNetwork besuA = env.besuNetworks().add(BesuNetworkSpec.singleNode());
        final BesuNetwork besuB = env.besuNetworks().add(BesuNetworkSpec.singleNode());

        final EvmEndpoint epA =
                env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-a").withDiscoverChannels(true));
        final EvmEndpoint epB =
                env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-b").withDiscoverChannels(true));

        // Phase the bring-up by hand: the endpoints have to be running with NO channel at all, which
        // is the state startAll() never leaves them in.
        env.besuNetworks().startAll();
        env.besuNetworks().deployAll();

        // Each endpoint operates its chain's service with an empty channel list. Discovery fills it in.
        epA.serve(besuA, List.of()).addPeerProofType(besuB.caip2(), "QBFT");
        epB.serve(besuB, List.of()).addPeerProofType(besuA.caip2(), "QBFT");
        env.endpoints().startAll();

        assertThat(epA.isHealthy()).isTrue();
        assertThat(epB.isHealthy()).isTrue();
        assertThat(epA.renderConfig())
                .as("the endpoint starts with discovery on and no pinned channels")
                .contains("discoverChannels: true")
                .contains("predefinedChannels: []");

        // Now wire a channel on chain. The endpoints are already running, so nothing reconfigures
        // them — completeChannel emits ChannelCompleted and their discovery loops must notice.
        final Channel channel =
                env.channels().create(ChannelSpec.between(besuA, besuB).endpoints(epA, epB));
        env.channels().wireForDiscovery(channel);

        // The observable proof of discovery: a sync cycle attributed to a channel id that did not
        // exist when the relay read its configuration.
        env.await().channelLive(epA, channel.metricLabel(), Duration.ofMinutes(3));
        env.await().channelLive(epB, channel.metricLabel(), Duration.ofMinutes(3));

        // And it must actually work, not merely appear: messages have to flow over the discovered
        // channel. A channel that is "live" but never delivers would pass the metric check alone.
        final InjectionRun run = env.messages().enqueue(channel.a(), 4, MessageShape.small());
        run.awaitAllConfirmed(Duration.ofMinutes(3));
        assertThat(run.reverted()).isZero();
        assertThat(run.confirmed()).isEqualTo(4);

        env.await().peerReceivedAll(channel.a(), run.messageIds(), Duration.ofMinutes(5));
        env.await().queueDrained(channel.a(), Duration.ofMinutes(5));

        assertThat(channel.b().state().receivedMessageId())
                .as("every message over the discovered channel was processed")
                .isEqualTo(4L);

        assertThat(epA.logs().errorLines()).isEmpty();
        assertThat(epB.logs().errorLines()).isEmpty();
        for (final BesuNetwork network : env.besuNetworks().all()) {
            assertThat(network.inspect()
                            .revertedTransactions(0, network.inspect().headBlockNumber()))
                    .as("no reverted transactions on " + network.id())
                    .isEmpty();
        }
    }

    @E2ETest(timeoutSeconds = 600)
    void wiring_for_discovery_is_rejected_when_an_endpoint_cannot_discover(final E2EEnvironment env) {
        final BesuNetwork besuA = env.besuNetworks().add(BesuNetworkSpec.singleNode());
        final BesuNetwork besuB = env.besuNetworks().add(BesuNetworkSpec.singleNode());

        // Discovery deliberately left off — the default.
        final EvmEndpoint epA = env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-a"));
        final EvmEndpoint epB = env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-b"));

        env.besuNetworks().startAll();
        env.besuNetworks().deployAll();

        final Channel channel =
                env.channels().create(ChannelSpec.between(besuA, besuB).endpoints(epA, epB));

        // Fail loudly instead of wiring a channel nobody will ever serve. Without this guard the
        // channel would exist on chain, both relays would look perfectly healthy, and the test would
        // time out later with nothing pointing at the cause.
        assertThatThrownBy(() -> env.channels().wireForDiscovery(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel discovery disabled")
                .hasMessageContaining("withDiscoverChannels(true)");
    }
}
