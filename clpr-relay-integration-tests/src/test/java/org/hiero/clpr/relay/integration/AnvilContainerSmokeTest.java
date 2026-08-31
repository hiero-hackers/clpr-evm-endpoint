// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: verifies that we can start an {@link AnvilContainer}, talk to it via our
 * {@link EvmJsonRpcClient}, and that it accepts client-signed EIP-1559 raw transactions —
 * which is what every other test in this module ultimately depends on.
 *
 * <p>Gated on the {@code RUN_INTEGRATION_TESTS} environment variable because the test
 * requires Docker; it is automatically skipped when Docker is not available.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
@Testcontainers
class AnvilContainerSmokeTest {

    @Container
    static final AnvilContainer ANVIL = new AnvilContainer();

    @Test
    void anvil_exposes_json_rpc_and_accepts_raw_signed_transaction() {
        final EvmJsonRpcClient rpc = new TestEvmJsonRpcClient(ANVIL.jsonRpcUrl());

        // Basic liveness: the chain produces blocks.
        final long blockNumber =
                rpc.ethGetBlockHeaderByNumber("latest").number().longValueExact();
        assertThat(blockNumber).isGreaterThanOrEqualTo(0L);

        // Submit a client-signed EIP-1559 raw transaction sending 0 wei to self.
        final var signer = new EthSigner(AnvilContainer.DEV_PRIVATE_KEY);
        final var submitter = new AnvilTxSubmitter(rpc, signer, AnvilContainer.CHAIN_ID);
        final String txHash = submitter.sendRawTx(AnvilContainer.DEV_ADDRESS, new byte[0], 0L, 0xffffffL);
        assertThat(txHash).startsWith("0x").hasSize(66);
    }
}
