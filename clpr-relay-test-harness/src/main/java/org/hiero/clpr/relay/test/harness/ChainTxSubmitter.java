// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import org.jspecify.annotations.Nullable;

/**
 * Signs and submits a transaction to an EVM chain on behalf of a single account. The harness's
 * on-chain control surface ({@code ChainActor}) drives the CLPR Service through this seam, so the same
 * actor works against any chain and any signing account — the test supplies the concrete submitter.
 *
 * <p>{@link Eip1559TxSubmitter} is the default implementation; integration tests may subclass it (e.g.
 * {@code AnvilTxSubmitter}) to pin chain-specific fee defaults.
 */
public interface ChainTxSubmitter {

    /**
     * Sign and submit a transaction; return its hash.
     *
     * @param to recipient address ({@code 0x}-prefixed), or {@code null} for a contract-creation tx
     * @param callData call data bytes (or constructor bytecode + args for a deployment)
     * @param valueWei value to transfer in wei (typically 0 for contract calls)
     * @param gasLimit gas limit
     * @return the transaction hash, {@code 0x}-prefixed
     */
    String sendRawTx(@Nullable String to, byte[] callData, long valueWei, long gasLimit);

    /** @return the sender address ({@code 0x}-prefixed) derived from the signing key. */
    String address();
}
