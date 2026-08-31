// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.hiero.clpr.relay.e2e.E2EEnvironment;
import org.hiero.clpr.relay.e2e.E2ETest;
import org.hiero.clpr.relay.e2e.chain.BesuNetwork;
import org.hiero.clpr.relay.e2e.chain.BesuNetworkSpec;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpoint;
import org.hiero.clpr.relay.e2e.endpoint.EvmEndpointSpec;
import org.hiero.clpr.relay.e2e.endpoint.PrometheusScrape;
import org.hiero.clpr.relay.integration.TestConditions;

/**
 * Validates the system under test as a container: that the staged image runs the real relay jars,
 * that the generated {@code relay.yaml} is accepted, that the signing key arrives through the
 * production entrypoint's sentinel substitution, and that the process is observable from outside.
 *
 * <p>No channels yet — this isolates "the endpoint runs and is observable" from "the endpoint does
 * CLPR work". Keeping those apart matters because a container that dies on a config error and a
 * channel that never comes up look identical from a test that only checks message delivery.
 */
class EndpointContainerE2ETest {

    @E2ETest(timeoutSeconds = 480)
    void endpoint_container_starts_and_serves_health_and_metrics(final E2EEnvironment env) {
        final BesuNetwork besu = env.besuNetworks().add(BesuNetworkSpec.singleNode());
        final EvmEndpoint endpoint =
                env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-a"));

        // The chain has to be declared and deployed before the endpoint's config can name the
        // service address, so wiring happens between the chain coming up and the endpoint starting.
        // startAll() enforces that order; here the config is attached in a callback-free way by
        // declaring the network first and configuring after deployment.
        env.besuNetworks().startAll();
        env.besuNetworks().deployAll();
        configure(endpoint, besu, List.of());

        endpoint.start();

        assertThat(endpoint.isRunning()).isTrue();
        // Awaited rather than sampled once: start()'s wait strategy already blocks on /healthz, but
        // relying on that alone would turn any change to the strategy into a confusing flake here.
        env.await().endpointHealthy(endpoint, Duration.ofMinutes(1));

        // The scrape is the assertion surface for a black-box SUT, so prove it parses into something
        // usable rather than merely returning 200.
        final PrometheusScrape scrape = endpoint.metrics();
        assertThat(scrape.raw()).contains("clpr_");
        assertThat(scrape.all()).as("at least one metric series is exposed").isNotEmpty();

        // The signing key reached the relay through the entrypoint's __SIGNING_KEY_*__ substitution.
        // If it had not, RelayConfig validation would have rejected the empty key and the container
        // would never have served /healthz — so a healthy endpoint plus a non-placeholder config is
        // the observable evidence.
        assertThat(endpoint.renderConfig())
                .as("the rendered config carries a sentinel, never the raw key")
                .contains("__SIGNING_KEY_EP_A__")
                .doesNotContain(endpoint.signerAccount().privateKeyHex());

        // The relay must not have logged an error while coming up.
        assertThat(endpoint.logs().errorLines())
                .as("relay startup produced no ERROR-level log lines")
                .isEmpty();
    }

    @E2ETest(timeoutSeconds = 600)
    void endpoint_survives_restart_with_a_stable_address(final E2EEnvironment env) {
        final BesuNetwork besu = env.besuNetworks().add(BesuNetworkSpec.singleNode());
        final EvmEndpoint endpoint =
                env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-a"));

        env.besuNetworks().startAll();
        env.besuNetworks().deployAll();
        configure(endpoint, besu, List.of());
        endpoint.start();

        final String addressBefore = endpoint.containerIp();
        final String signerBefore = endpoint.signingAddress();
        assertThat(endpoint.isHealthy()).isTrue();

        endpoint.restart();

        TestConditions.awaitCondition(Duration.ofMinutes(1), endpoint::isHealthy);

        // A restart must keep the address and the key. The address is published verbatim in the
        // on-chain peer roster when a channel is wired, so a moved endpoint would leave every peer
        // dialling a dead address; a changed key would submit from an unregistered account.
        assertThat(endpoint.containerIp()).isEqualTo(addressBefore);
        assertThat(endpoint.signingAddress()).isEqualTo(signerBefore);

        // Log capture spans the restart — the buffer is owned by the framework, not the container,
        // which is exactly what makes post-mortem diagnosis possible after a crash.
        assertThat(endpoint.logs().lines()).isNotEmpty();
        assertThat(endpoint.logs().errorLines()).isEmpty();
    }

    @E2ETest(timeoutSeconds = 480)
    void metrics_scrape_parses_labelled_counter_families(final E2EEnvironment env) {
        final BesuNetwork besu = env.besuNetworks().add(BesuNetworkSpec.singleNode());
        final EvmEndpoint endpoint =
                env.endpoints().add(EvmEndpointSpec.defaults().withId("ep-a"));

        env.besuNetworks().startAll();
        env.besuNetworks().deployAll();
        configure(endpoint, besu, List.of());
        endpoint.start();

        final PrometheusScrape scrape = endpoint.metrics();

        // An absent counter reads as zero: the registry creates a labelled series lazily on first
        // increment, so "no series" and "zero" are the same observation. Assertions across the suite
        // depend on that, so pin it here.
        assertThat(scrape.counter("clpr_evm_tx_reverts", Map.of("channel_id", "0xdeadbeef")))
                .isZero();
        assertThat(scrape.counterSum("clpr_evm_tx_reverts")).isZero();

        // A gauge, by contrast, distinguishes absent from zero — zero is a meaningful reading for
        // the failing-channel gauges, so an absent one must not masquerade as healthy.
        assertThat(scrape.gauge("clpr_does_not_exist", Map.of())).isNaN();
    }

    /**
     * Attach the chain and its deployed service to the endpoint's configuration.
     *
     * <p>No channels: channel wiring is exercised by the channel-level suites, and this class is
     * deliberately about "the endpoint runs and is observable" alone.
     */
    private static void configure(final EvmEndpoint endpoint, final BesuNetwork besu, final List<String> channelIds) {
        endpoint.serve(besu, channelIds);
    }
}
