// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.clpr.relay.core.metrics.MetricLabels;
import org.hiero.clpr.relay.evm.AccountTransactionSubmitter;
import org.hiero.clpr.relay.evm.Eip1559GasStrategy;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.sei.CometBftRpcClient;
import org.jspecify.annotations.Nullable;

/**
 * Per-local-network shared infrastructure: the JSON-RPC client, the EIP-1559 gas strategy, and —
 * for {@link ProofType#CometBFT} networks — the CometBFT RPC client. Built once per
 * {@link RelayConfig.LocalNetworkConfig} and shared by every {@link ClprServiceHandler} (and its
 * {@link ClprChannelHandler}s) that targets that network.
 *
 * <p>All held clients are stateless with respect to a single channel, so a single adapter is
 * safely shared across the channels and services that live on the same chain.
 */
public final class LocalNetworkAdapter {

    private static final Logger LOGGER = Loggers.getLogger(LocalNetworkAdapter.class);

    /**
     * Bounded capacity of each <em>per-channel</em> submit queue inside an
     * {@link AccountTransactionSubmitter}. Exact-duplicate re-offers are suppressed at enqueue, so in
     * steady state a channel holds only its handful of distinct pending bundles; this large ceiling
     * is headroom to ride out a sustained submission stall (e.g. a slow node under a burst of distinct
     * bundles) without dropping, and only beyond it is the offending channel's own excess dropped —
     * never affecting any other channel. Total worst-case memory is this times the number of
     * channels sharing the account.
     */
    static final int PER_CHANNEL_SUBMIT_QUEUE_CAPACITY = 1024;

    private final RelayConfig.LocalNetworkConfig config;
    private final EvmJsonRpcClient rpcClient;
    private final Eip1559GasStrategy gasStrategy;
    private final Metrics metrics;

    @Nullable
    private final CometBftRpcClient cometBftRpcClient;

    /**
     * Shared per-signing-account submitters for this network's chain, keyed by the lowercased signing
     * address. The nonce space is per {@code (chain, account)}, so every channel/service on this
     * network that signs from the same key shares one {@link AccountTransactionSubmitter}, which keeps
     * at most one transaction per account in flight.
     */
    private final Map<String, AccountTransactionSubmitter> accountSubmitters = new ConcurrentHashMap<>();

    /**
     * Build the shared clients for a local network from its configuration. A CometBFT network also
     * gets a {@link CometBftRpcClient}; QBFT networks do not.
     *
     * @param config  the local network configuration
     * @param metrics metrics registry shared by every account submitter on this network
     * @return a ready-to-share adapter
     */
    public static LocalNetworkAdapter create(final RelayConfig.LocalNetworkConfig config, final Metrics metrics) {
        final var evmParams = config.evm();
        final var rpcClient = new EvmJsonRpcClient(
                evmParams.jsonRpcUrl(), evmParams.maxRpcRetries(), Duration.ofMillis(evmParams.requestTimeoutMs()));
        final var gasStrategy = new Eip1559GasStrategy(
                rpcClient, evmParams.maxGasPriceCap(), evmParams.gasPriorityFee(), evmParams.gasBufferMultiplier());
        final CometBftRpcClient cometBftRpcClient;
        if (config.proofType() == ProofType.CometBFT) {
            final var cometBft = config.cometBft();
            cometBftRpcClient = new CometBftRpcClient(
                    cometBft.cometBftRpcUrl(), cometBft.maxRetries(), Duration.ofMillis(cometBft.requestTimeoutMs()));
        } else {
            cometBftRpcClient = null;
        }
        return new LocalNetworkAdapter(config, rpcClient, gasStrategy, metrics, cometBftRpcClient);
    }

    LocalNetworkAdapter(
            final RelayConfig.LocalNetworkConfig config,
            final EvmJsonRpcClient rpcClient,
            final Eip1559GasStrategy gasStrategy,
            final Metrics metrics,
            @Nullable final CometBftRpcClient cometBftRpcClient) {
        this.config = config;
        this.rpcClient = rpcClient;
        this.gasStrategy = gasStrategy;
        this.metrics = metrics;
        this.cometBftRpcClient = cometBftRpcClient;

        // Per-network base-fee gauge, tagged network, refreshed on each metrics scrape. The base fee
        // sets the floor of every EIP-1559 submission's max-fee, so a spike here explains rising
        // submission cost (and, paired with evm.account.balance_eth, a draining signing account).
        final LongGauge baseFeeGauge = metrics.getOrCreate(
                new LongGauge.Config("evm", MetricLabels.labeled("gas_price.base_fee_wei", "network", config.id()))
                        .withDescription("Last observed EIP-1559 base fee per gas, in wei"));
        metrics.addUpdater(() -> {
            try {
                final BigInteger baseFee =
                        rpcClient.ethGetBlockHeaderByNumber("latest").baseFeePerGas();
                if (baseFee != null) {
                    baseFeeGauge.set(baseFee.longValueExact());
                }
            } catch (final RuntimeException e) {
                LOGGER.warn("failed to read base fee for network {}", e, config.id());
            }
        });
    }

    /** The local network configuration this adapter was built from. */
    public RelayConfig.LocalNetworkConfig config() {
        return config;
    }

    /** The network id. */
    public String id() {
        return config.id();
    }

    /** The proof type of this local network. */
    public ProofType proofType() {
        return config.proofType();
    }

    /** The shared EVM JSON-RPC client. */
    public EvmJsonRpcClient rpcClient() {
        return rpcClient;
    }

    /** The shared EIP-1559 gas strategy. */
    public Eip1559GasStrategy gasStrategy() {
        return gasStrategy;
    }

    /** The shared CometBFT RPC client, present only for {@link ProofType#CometBFT} networks. */
    public Optional<CometBftRpcClient> cometBftRpcClient() {
        return Optional.ofNullable(cometBftRpcClient);
    }

    /**
     * The shared {@link AccountTransactionSubmitter} for {@code signer}'s account on this network's
     * chain, created and started on first request. Channels/services signing from the same address
     * share one submitter, so at most one transaction per account is ever in flight.
     *
     * @param signer the ECDSA signer whose account the returned submitter serves
     * @return the shared, already-started account submitter for that account on this chain
     */
    public AccountTransactionSubmitter accountSubmitterFor(final EthSigner signer) {
        return accountSubmitters.computeIfAbsent(signer.address().toLowerCase(Locale.ROOT), key -> {
            final var submitter = new AccountTransactionSubmitter(
                    rpcClient,
                    signer,
                    config.evm().chainId(),
                    config.id(),
                    gasStrategy,
                    metrics,
                    PER_CHANNEL_SUBMIT_QUEUE_CAPACITY);
            submitter.start();
            return submitter;
        });
    }

    /** Stop every account submitter this adapter has created, releasing their worker threads. */
    public void close() {
        for (final var submitter : accountSubmitters.values()) {
            submitter.stop();
        }
    }
}
