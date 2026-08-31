// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.load;

import org.hiero.clpr.relay.e2e.chain.TestAccount;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.jspecify.annotations.Nullable;

/**
 * Submits transactions for one account without waiting for receipts, tracking the nonce locally.
 *
 * <p>The harness's default submitter reads a fresh {@code pending} nonce before every send, which is
 * correct for sequential setup calls and useless for load: the second send reuses the first's nonce
 * until the first is mined, so throughput collapses to one transaction per block and concurrent sends
 * collide outright. Counting locally lets a whole batch enter the txpool at once.
 *
 * <p>Deliberately dumb beyond that: no receipt polling, no retry, no fee escalation. The caller
 * reconciles receipts afterwards, which is what makes "how many of these actually landed" an
 * observation rather than an assumption.
 *
 * <p>Per-sender depth is bounded by the node's txpool ({@code tx-pool-max-future-by-sender}, 200 in
 * Besu by default), so a batch larger than that has to be spread over several accounts — see
 * {@link MessageInjector}.
 */
public final class PipelinedTxSubmitter {

    /** 1 gwei priority fee. */
    private static final long MAX_PRIORITY_FEE_PER_GAS = 1_000_000_000L;

    /** 100 gwei ceiling — far above any dev-chain base fee, so a send is never priced out. */
    private static final long MAX_FEE_PER_GAS = 100_000_000_000L;

    private final EvmJsonRpcClient rpc;
    private final EthSigner signer;
    private final long chainId;
    private final TestAccount account;

    private long nextNonce = -1L;

    public PipelinedTxSubmitter(final EvmJsonRpcClient rpc, final TestAccount account, final long chainId) {
        this.rpc = rpc;
        this.account = account;
        this.signer = new EthSigner(account.privateKeyHex());
        this.chainId = chainId;
    }

    /** @return the sending account. */
    public TestAccount account() {
        return account;
    }

    /** @return the sender address. */
    public String address() {
        return account.address();
    }

    /**
     * Sign and submit at the next local nonce.
     *
     * @param to target address, or {@code null} for a contract creation
     * @param callData call data
     * @param valueWei value to attach
     * @param gasLimit gas limit
     * @return the transaction hash
     */
    public synchronized String submit(
            @Nullable final String to, final byte[] callData, final long valueWei, final long gasLimit) {
        if (nextNonce < 0L) {
            // Seed from `pending` once: anything this account already has in flight (its endpoint
            // registration, say) must be counted or the first send would reuse a live nonce.
            nextNonce = rpc.ethGetTransactionCount(account.address(), "pending");
        }
        final long nonce = nextNonce++;
        final byte[] rawTx = signer.signEip1559Transaction(
                nonce, MAX_PRIORITY_FEE_PER_GAS, MAX_FEE_PER_GAS, gasLimit, to, valueWei, callData, chainId);
        return rpc.ethSendRawTransaction(AbiCodec.toHex(rawTx));
    }

    /**
     * Forget the cached nonce so the next send re-reads it from the node.
     *
     * <p>Needed after anything that moves the account's nonce behind this submitter's back — another
     * submitter for the same account, or a dropped transaction that never mined.
     */
    public synchronized void resetNonce() {
        nextNonce = -1L;
    }
}
