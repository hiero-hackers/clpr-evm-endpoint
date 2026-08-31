// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.test.harness.Eip1559TxSubmitter;

/**
 * Anvil-flavoured {@link Eip1559TxSubmitter} — a thin alias retained so the existing Anvil-backed tests
 * keep constructing {@code new AnvilTxSubmitter(rpc, signer, chainId)}. The actual EIP-1559 signing logic
 * now lives in the reusable {@link Eip1559TxSubmitter} (a {@code ChainTxSubmitter}) in the test harness.
 */
public final class AnvilTxSubmitter extends Eip1559TxSubmitter {

    public AnvilTxSubmitter(final EvmJsonRpcClient rpc, final EthSigner signer, final long chainId) {
        super(rpc, signer, chainId);
    }
}
