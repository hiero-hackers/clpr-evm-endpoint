// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.endpoint;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Declarative description of one endpoint container (the system under test). Immutable; use the
 * {@code with*} methods.
 *
 * @param id stable identifier, also the container network alias and log prefix
 * @param signingKeyHex secp256k1 key the relay signs {@code submitBundle} transactions and outbound
 *     sync payloads with; {@code null} claims a fresh account from the pool. Every endpoint needs its
 *     own: the relay serialises submissions per signing account, so two endpoints sharing a key would
 *     race the same nonce sequence and produce failures indistinguishable from a relay bug
 * @param pollInterval how often the listener re-reads on-chain channel state
 * @param maxMessagesPerBundle messages the relay packs into one sync bundle. A first-class knob
 *     because the relay currently cannot drain a backlog larger than this value — it bundles the
 *     first batch but carries a running hash for the whole queue, so the peer rejects every bundle
 *     and the channel wedges (clpr-hiero#171). Tests both avoid and provoke that by moving this
 * @param backoffBaseMs first-failure backoff for the per-channel loops
 * @param backoffCapMs backoff ceiling
 * @param requestTimeoutMs per-request JSON-RPC timeout
 * @param maxRpcRetries JSON-RPC retry attempts on transient errors
 * @param maxGasPriceCapWei hard ceiling on {@code maxFeePerGas}; {@link Long#MAX_VALUE} disables it
 * @param discoverChannels poll the service for {@code ChannelCompleted} events instead of relying
 *     only on the channel ids wired in at startup
 * @param jvmOpts extra JVM flags; the heap cap is added from the active profile
 */
public record EvmEndpointSpec(
        String id,
        @Nullable String signingKeyHex,
        Duration pollInterval,
        int maxMessagesPerBundle,
        long backoffBaseMs,
        long backoffCapMs,
        long requestTimeoutMs,
        int maxRpcRetries,
        long maxGasPriceCapWei,
        boolean discoverChannels,
        List<String> jvmOpts) {

    public EvmEndpointSpec {
        if (maxMessagesPerBundle < 1) {
            throw new IllegalArgumentException("maxMessagesPerBundle must be >= 1, got " + maxMessagesPerBundle);
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        jvmOpts = List.copyOf(jvmOpts);
    }

    /**
     * Defaults tuned for a test rather than for production.
     *
     * <p>The JSON-RPC timeout and retry count are deliberately tight (2 s, one retry) so a node
     * outage surfaces as a channel failure in seconds instead of the ~2 minutes the production
     * defaults (30 s x 4 attempts) would take — the same reasoning the integration suite's relay
     * config uses. {@code maxMessagesPerBundle} stays at the production default of 10 so tests
     * observe real batching behaviour unless they opt out.
     *
     * @return the default spec
     */
    public static EvmEndpointSpec defaults() {
        return new EvmEndpointSpec(
                "endpoint",
                null,
                Duration.ofSeconds(1),
                10,
                1_000L,
                30_000L,
                2_000L,
                1,
                Long.MAX_VALUE,
                false,
                List.of());
    }

    public EvmEndpointSpec withId(final String newId) {
        return new EvmEndpointSpec(
                newId,
                signingKeyHex,
                pollInterval,
                maxMessagesPerBundle,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                maxGasPriceCapWei,
                discoverChannels,
                jvmOpts);
    }

    public EvmEndpointSpec withSigningKeyHex(final String keyHex) {
        return new EvmEndpointSpec(
                id,
                keyHex,
                pollInterval,
                maxMessagesPerBundle,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                maxGasPriceCapWei,
                discoverChannels,
                jvmOpts);
    }

    public EvmEndpointSpec withPollInterval(final Duration interval) {
        return new EvmEndpointSpec(
                id,
                signingKeyHex,
                interval,
                maxMessagesPerBundle,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                maxGasPriceCapWei,
                discoverChannels,
                jvmOpts);
    }

    public EvmEndpointSpec withMaxMessagesPerBundle(final int count) {
        return new EvmEndpointSpec(
                id,
                signingKeyHex,
                pollInterval,
                count,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                maxGasPriceCapWei,
                discoverChannels,
                jvmOpts);
    }

    public EvmEndpointSpec withMaxGasPriceCapWei(final long wei) {
        return new EvmEndpointSpec(
                id,
                signingKeyHex,
                pollInterval,
                maxMessagesPerBundle,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                wei,
                discoverChannels,
                jvmOpts);
    }

    public EvmEndpointSpec withDiscoverChannels(final boolean discover) {
        return new EvmEndpointSpec(
                id,
                signingKeyHex,
                pollInterval,
                maxMessagesPerBundle,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                maxGasPriceCapWei,
                discover,
                jvmOpts);
    }

    public EvmEndpointSpec withJvmOpts(final List<String> opts) {
        return new EvmEndpointSpec(
                id,
                signingKeyHex,
                pollInterval,
                maxMessagesPerBundle,
                backoffBaseMs,
                backoffCapMs,
                requestTimeoutMs,
                maxRpcRetries,
                maxGasPriceCapWei,
                discoverChannels,
                opts);
    }
}
