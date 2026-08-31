// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfigurationResponse;

/**
 * Produces the ledger configuration payload served by the local CLPR endpoint's
 * {@code getLedgerConfiguration} gRPC. Implementations encapsulate the ledger-specific
 * work needed to assemble the payload — reading the on-chain configuration, the current
 * block header, EIP-1186 storage proofs, and any consensus-type-specific trust-anchor
 * material (e.g. a QBFT epoch block header).
 *
 * <p>The response is a {@link ClprLedgerConfigurationResponse} wrapper. For QBFT chains
 * the {@code qbft} field is set
 * ({@link com.hedera.hapi.node.state.clpr.QbftLedgerConfigurationPayload}).
 *
 * <p>Decoupling this from {@link ContractStateReader} keeps the read-only state reader
 * free of ledger-specific framing concerns: a state reader that returns just the on-chain
 * {@link com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration} remains useful to the
 * sync loop and the protocol-version bootstrap check, while the heavier payload assembled
 * here is consumed only by the gRPC handler.
 */
public interface LedgerConfigurationPayloadProvider {

    /**
     * Build the {@code getLedgerConfiguration} response at the given commitment level.
     * Implementations decide which RPC roundtrips to make to assemble it, and are
     * responsible for binding all reads to the same block to keep the payload internally
     * consistent.
     *
     * @param commitmentLevel the commitment level at which the underlying ledger state is read
     * @return the response wrapper with the appropriate payload field set
     */
    ClprLedgerConfigurationResponse provide(CommitmentLevel commitmentLevel);
}
