// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.jspecify.annotations.Nullable;

/**
 * Minimal client-side signing helper for Sei EVM integration tests.
 *
 * <p>Signs an EIP-1559 (type-2) transaction with the configured {@link EthSigner} and submits it
 * via {@code eth_sendRawTransaction}. Each call fetches a fresh {@code pending}-nonce (no nonce
 * cache — tests are sequential). Fee constants are set well above the Sei devnet's minimum gas
 * price so transactions land regardless of the baseFee configured at genesis.
 *
 * <p>Unlike {@link AnvilTxSubmitter}, this class includes receipt polling via
 * {@link #sendAndWait} because Sei block times (~400 ms) differ from Anvil's instant-mine
 * mode, and test setup needs to wait for on-chain confirmation before querying state proofs.
 */
public final class SeiTxSubmitter {

    /** 1 gwei priority fee — above Sei devnet minimum. */
    private static final long MAX_PRIORITY_FEE_PER_GAS = 1_000_000_000L;
    /** 100 gwei max fee — well above any Sei devnet baseFee. */
    private static final long MAX_FEE_PER_GAS = 100_000_000_000L;

    private static final int RECEIPT_POLL_ATTEMPTS = 120;
    private static final long RECEIPT_POLL_INTERVAL_MS = 500L;

    private final EvmJsonRpcClient rpc;
    private final EthSigner signer;
    private final long chainId;

    public SeiTxSubmitter(final EvmJsonRpcClient rpc, final EthSigner signer, final long chainId) {
        this.rpc = rpc;
        this.signer = signer;
        this.chainId = chainId;
    }

    /** @return the sender address (derived from the signer's private key) */
    public String address() {
        return signer.address();
    }

    /**
     * Sign and submit a transaction as an EIP-1559 raw envelope; return the tx hash.
     *
     * @param to       recipient address (with {@code 0x} prefix), or {@code null} for contract creation
     * @param callData call data bytes (or constructor bytecode for deployment)
     * @param valueWei value to transfer in wei (typically 0 for contract calls)
     * @param gasLimit gas limit
     * @return the transaction hash hex string
     */
    public String sendRawTx(
            @Nullable final String to, final byte[] callData, final long valueWei, final long gasLimit) {
        final long nonce = rpc.ethGetTransactionCount(signer.address(), "pending");
        final byte[] rawTx = signer.signEip1559Transaction(
                nonce, MAX_PRIORITY_FEE_PER_GAS, MAX_FEE_PER_GAS, gasLimit, to, valueWei, callData, chainId);
        return rpc.ethSendRawTransaction(AbiCodec.toHex(rawTx));
    }

    /**
     * Sign, submit, and block until the receipt is available; return the receipt.
     *
     * <p>Throws {@link IllegalStateException} if the transaction reverts or the receipt is
     * not found within the poll window.
     *
     * @param to       recipient address, or {@code null} for contract creation
     * @param callData call data bytes
     * @param valueWei value in wei
     * @param gasLimit gas limit
     * @return the transaction receipt as a {@link JsonNode}
     * @throws InterruptedException if the polling thread is interrupted
     */
    public JsonNode sendAndWait(
            @Nullable final String to, final byte[] callData, final long valueWei, final long gasLimit)
            throws InterruptedException {
        return waitForReceipt(sendRawTx(to, callData, valueWei, gasLimit));
    }

    /**
     * Poll until the receipt for {@code txHash} is available; return it.
     *
     * <p>Throws {@link IllegalStateException} on revert ({@code status == 0x0}) or timeout.
     *
     * @param txHash the transaction hash to poll for
     * @return the confirmed receipt
     * @throws InterruptedException if interrupted while polling
     */
    public JsonNode waitForReceipt(final String txHash) throws InterruptedException {
        for (int i = 0; i < RECEIPT_POLL_ATTEMPTS; i++) {
            final JsonNode receipt = rpc.ethGetTransactionReceipt(txHash);
            if (receipt != null && !receipt.isNull()) {
                final JsonNode statusNode = receipt.get("status");
                if (statusNode != null && !statusNode.isNull()) {
                    final String status = statusNode.asText();
                    if ("0x0".equalsIgnoreCase(status) || "0".equals(status)) {
                        throw new IllegalStateException("Transaction reverted: " + txHash + " receipt=" + receipt);
                    }
                }
                return receipt;
            }
            Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
        }
        throw new IllegalStateException("Receipt not found for tx " + txHash + " after "
                + (RECEIPT_POLL_ATTEMPTS * RECEIPT_POLL_INTERVAL_MS / 1000) + "s");
    }
}
