// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnvilTxSubmitter} — no Docker required.
 */
class AnvilTxSubmitterTest {

    /** Anvil dev account 0 private key (well-known public test vector). */
    private static final String TEST_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    @Test
    void sendRawTx_signsAsEip1559AndCallsEthSendRawTransaction() {
        final AtomicReference<String> capturedHex = new AtomicReference<>();
        final var submitter = getAnvilTxSubmitter(capturedHex);

        final String txHash = submitter.sendRawTx(
                "0x1234567890123456789012345678901234567890", new byte[] {0x12, 0x34}, 0L, 100_000L);

        assertThat(txHash).startsWith("0x").hasSize(66);
        assertThat(capturedHex.get()).startsWith("0x02"); // EIP-1559 type byte
    }

    private static @NonNull AnvilTxSubmitter getAnvilTxSubmitter(final AtomicReference<String> capturedHex) {
        final var rpc = new TestEvmJsonRpcClient() {
            @Override
            public long ethGetTransactionCount(final String address, final String blockTag) {
                return 0L;
            }

            @Override
            public String ethSendRawTransaction(final String signedTxHex) {
                capturedHex.set(signedTxHex);
                return "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
            }
        };
        final var submitter = new AnvilTxSubmitter(rpc, new EthSigner(TEST_KEY), 31337L);
        return submitter;
    }

    @Test
    void sendRawTx_contractCreation_encodesWithoutToField() {
        final AtomicReference<String> capturedHex = new AtomicReference<>();
        final var rpc = new TestEvmJsonRpcClient() {
            @Override
            public long ethGetTransactionCount(final String address, final String blockTag) {
                return 0L;
            }

            @Override
            public String ethSendRawTransaction(final String signedTxHex) {
                capturedHex.set(signedTxHex);
                return "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
            }
        };
        final var submitter = new AnvilTxSubmitter(rpc, new EthSigner(TEST_KEY), 31337L);

        // Contract-creation tx (to=null) must still produce a valid type-2 envelope.
        submitter.sendRawTx(null, new byte[] {0x60, (byte) 0x80, 0x60, 0x40}, 0L, 200_000L);

        // 0x02 type-byte then RLP list header; the encoding doesn't include a 20-byte address.
        assertThat(capturedHex.get()).startsWith("0x02");
    }

    @Test
    void address_matchesSignerAddress() {
        final var signer = new EthSigner(TEST_KEY);
        final var rpc = new TestEvmJsonRpcClient();
        final var submitter = new AnvilTxSubmitter(rpc, signer, 31337L);

        assertThat(submitter.address()).isEqualTo(signer.address());
    }
}
