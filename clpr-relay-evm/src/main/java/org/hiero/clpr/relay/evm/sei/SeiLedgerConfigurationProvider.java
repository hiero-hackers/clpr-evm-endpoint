// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.sei;

import static org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient.toBlockTag;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfigurationResponse;
import com.hedera.hapi.node.state.clpr.ClprSeiLedgerConfigurationPayload;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.LedgerConfigurationPayloadProvider;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;

/**
 * Sei/CometBFT implementation of {@link LedgerConfigurationPayloadProvider}.
 *
 * <p>Assembles the Sei-specific ledger configuration payload by reading three pieces of state
 * and binding them to a single block height:
 *
 * <ol>
 *   <li>The current EVM block header at the requested commitment level — resolved once via
 *       {@code eth_getBlockByNumber}. On Sei the EVM block number and CometBFT height are 1:1,
 *       so this also pins the CometBFT-side read.</li>
 *   <li>The on-chain {@code ClprLedgerConfiguration} read via {@link ContractStateReader}
 *       <b>at the exact block number from step 1</b>, not at the symbolic tag. This avoids
 *       a race where the symbolic tag resolves to a different block on the second call.</li>
 *   <li>The validator set at {@code stateHeight + 1} — the set that signs the CometBFT header
 *       committing block {@code stateHeight}'s state — which the peer's verifier seeds its
 *       trust anchor from.</li>
 * </ol>
 *
 * <p>Why a separate provider rather than decorating {@link ContractStateReader}: same reason
 * as {@code QbftLedgerConfigurationProvider} — internal consumers don't need the validator set,
 * and a decorator would pay the extra round-trip on every sync cycle. The provider is only
 * invoked from the gRPC {@code getLedgerConfiguration} pipeline.
 */
public final class SeiLedgerConfigurationProvider implements LedgerConfigurationPayloadProvider {

    private final EvmJsonRpcClient rpcClient;
    private final ContractStateReader stateReader;
    private final CometBftRpcClient cometBftRpcClient;

    /**
     * @param rpcClient           the EVM JSON-RPC client used to resolve the commitment level to a
     *                            concrete block number and to read contract state
     * @param stateReader         the contract state reader used to read the ledger configuration
     * @param cometBftRpcClient   the CometBFT RPC client used to fetch the validator set
     */
    public SeiLedgerConfigurationProvider(
            final EvmJsonRpcClient rpcClient,
            final ContractStateReader stateReader,
            final CometBftRpcClient cometBftRpcClient) {
        this.rpcClient = rpcClient;
        this.stateReader = stateReader;
        this.cometBftRpcClient = cometBftRpcClient;
    }

    @Override
    public ClprLedgerConfigurationResponse provide(final CommitmentLevel commitmentLevel) {
        final var currentHeader = rpcClient.ethGetBlockHeaderByNumber(commitmentLevel.toBlockTag());
        final var previousBlockTag = toBlockTag(currentHeader.number().longValueExact() - 1);
        final var ledgerConfig = stateReader.readLedgerConfiguration(previousBlockTag);
        final var validatorSet =
                cometBftRpcClient.getValidatorSet(currentHeader.number().longValueExact());
        return ClprLedgerConfigurationResponse.newBuilder()
                .sei(ClprSeiLedgerConfigurationPayload.newBuilder()
                        .initialValidatorSet(validatorSet)
                        .initialValidatorSetHeight(currentHeader.number().longValueExact())
                        .ledgerConfiguration(ledgerConfig)
                        .build())
                .build();
    }
}
