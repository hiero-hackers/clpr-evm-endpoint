// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link ChainTxSubmitter}: a minimal client-side signer for tests.
 *
 * <p>Signs an EIP-1559 (type-2) transaction with the configured {@link EthSigner} and submits it via
 * {@code eth_sendRawTransaction}. Each call fetches a fresh {@code pending} nonce (no nonce cache — tests
 * are sequential), and uses generous fixed fees that satisfy any dev chain regardless of base fee. This
 * is intentionally not the production submitter: no gas-cap protection, no retry/backoff, no receipt
 * polling — callers handle their own receipt logic.
 */
public class Eip1559TxSubmitter implements ChainTxSubmitter {

    /** 1 gwei priority. */
    private static final long MAX_PRIORITY_FEE_PER_GAS = 1_000_000_000L;
    /** 100 gwei max fee — well above any dev-chain base fee. */
    private static final long MAX_FEE_PER_GAS = 100_000_000_000L;

    private final EvmJsonRpcClient rpc;
    private final EthSigner signer;
    private final long chainId;

    public Eip1559TxSubmitter(final EvmJsonRpcClient rpc, final EthSigner signer, final long chainId) {
        this.rpc = rpc;
        this.signer = signer;
        this.chainId = chainId;
    }

    @Override
    public String address() {
        return signer.address();
    }

    @Override
    public String sendRawTx(
            @Nullable final String to, final byte[] callData, final long valueWei, final long gasLimit) {
        final long nonce = rpc.ethGetTransactionCount(signer.address(), "pending");
        final byte[] rawTx = signer.signEip1559Transaction(
                nonce, MAX_PRIORITY_FEE_PER_GAS, MAX_FEE_PER_GAS, gasLimit, to, valueWei, callData, chainId);
        return rpc.ethSendRawTransaction(AbiCodec.toHex(rawTx));
    }
}
