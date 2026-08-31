// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

/**
 * Proof format discriminator for a local network or peer channel.
 *
 * <p>The relay's core abstractions ({@code ContractStateReader}, {@code TransactionSubmitter},
 * {@code BundleConstructor}, {@code BundlePayloadCodec}) are chain-agnostic. This enum selects which
 * concrete implementation bundle to build in {@code ClprChannelHandler.create()} and which
 * {@code getLedgerConfiguration} payload type to serve.
 *
 * <p>New proof types are added by: (1) adding an enum constant here, (2) adding a corresponding
 * network-params record to {@link RelayConfig}, (3) implementing
 * {@link org.hiero.clpr.relay.core.LedgerConfigurationPayloadProvider}, (4) wiring a new
 * {@code BundleConstructor} + {@code LedgerConfigurationPayloadProvider} case in the proof-type
 * switch in {@code ClprChannelHandler.create()}, and (5) wiring a new {@code BundlePayloadCodec}
 * case in {@code ClprChannelHandler.codecFor()}.
 */
public enum ProofType {
    /**
     * EVM chain using QBFT consensus (e.g. Hyperledger Besu).
     * {@code getLedgerConfiguration} returns a {@code QbftLedgerConfigurationPayload} carrying
     * the epoch-boundary block header whose {@code extra_data} encodes the validator set.
     */
    QBFT,
    CometBFT,
    Hiero
}
