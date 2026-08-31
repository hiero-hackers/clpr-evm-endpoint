// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: verifies that we can start a {@link BesuContainer}, talk to it via our
 * {@link EvmJsonRpcClient}, and that it accepts client-signed EIP-1559 raw transactions.
 * Besu in dev mode does not provide an unlocked-wallet {@code eth_sendTransaction}, so this
 * test confirms that the migration to client-side signing (issue #29) actually works against
 * a production-style EVM node — not just Anvil.
 *
 * <p>Gated on the {@code RUN_INTEGRATION_TESTS} environment variable because the test
 * requires Docker; it is automatically skipped when Docker is not available (e.g. in
 * sandboxed CI environments without DinD).
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
@Testcontainers
class BesuContainerSmokeTest {

    @Container
    static final BesuContainer BESU = new BesuContainer();

    @Test
    void besu_exposes_json_rpc() {
        final EvmJsonRpcClient rpc = new TestEvmJsonRpcClient(BESU.jsonRpcUrl());
        final long blockNumber =
                rpc.ethGetBlockHeaderByNumber("latest").number().longValueExact();
        assertThat(blockNumber).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void besu_accepts_client_signed_eip1559_raw_transaction() {
        // The central premise of issue #29: production-style EVM nodes (here Besu in QBFT mode)
        // do not expose eth_sendTransaction, so the relay must client-sign every transaction.
        // We assert that Besu accepts a client-signed EIP-1559 raw transaction submitted via
        // eth_sendRawTransaction — i.e. it validates the signature, EIP-155 chain id, EIP-1559
        // envelope, nonce, and fee fields, and returns a tx hash. We do not poll for receipts:
        // mining policy under QBFT is orthogonal to whether the sign/submit path is correct,
        // and tying the test to block-time scheduling makes it flaky on slow CI.
        final EvmJsonRpcClient rpc = new TestEvmJsonRpcClient(BESU.jsonRpcUrl());
        final var signer = new EthSigner(BesuContainer.DEV_PRIVATE_KEY);
        final var submitter = new AnvilTxSubmitter(rpc, signer, BesuContainer.CHAIN_ID);

        // Send 0 wei to self — minimal raw tx that exercises the full sign → submit path.
        final String txHash = submitter.sendRawTx(BesuContainer.DEV_ADDRESS, new byte[0], 0L, 0xffffffL);

        assertThat(txHash).startsWith("0x").hasSize(66);
    }

    @Test
    void besu_dev_instance_contains_an_account_with_a_non_empty_storage_slot() {
        final EvmJsonRpcClient rpc = new TestEvmJsonRpcClient(BESU.jsonRpcUrl());

        // See: besu-genesis.json
        final var accountAddress = "0x1234567890123456789012345678901234567890";
        final var slotNumberHex = "0x55054e464089ef770b2e54e1c87d47a31a773ad6c848d0aac90495013c60f112";
        final var proof = rpc.ethGetProof(accountAddress, new String[] {slotNumberHex}, "latest");

        // We're expecting to find one storage proof in the response
        Assertions.assertEquals(1, proof.storageProof().size());
    }
}
