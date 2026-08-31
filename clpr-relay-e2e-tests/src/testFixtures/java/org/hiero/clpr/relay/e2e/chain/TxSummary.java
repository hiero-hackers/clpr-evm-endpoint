// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import org.jspecify.annotations.Nullable;

/**
 * A mined transaction, flattened from its block entry and receipt.
 *
 * <p>{@code success} comes from the receipt status, not from the transaction being mined: a reverted
 * transaction is mined and charged just like a successful one. That distinction is the whole point
 * of the E2E assertions around submission health — "the relay landed the transaction" and "the
 * relay's transaction did what it was supposed to" are different claims.
 *
 * @param hash transaction hash
 * @param blockNumber block it was mined in
 * @param nonce sender nonce
 * @param from sender address, lowercase
 * @param to recipient address, lowercase; {@code null} for a contract creation
 * @param success receipt status {@code 0x1}
 * @param gasUsed gas consumed
 * @param revertReason decoded revert data when available, else {@code null}
 */
public record TxSummary(
        String hash,
        long blockNumber,
        long nonce,
        String from,
        @Nullable String to,
        boolean success,
        long gasUsed,
        @Nullable String revertReason) {

    @Override
    public String toString() {
        return "tx[" + hash + " block=" + blockNumber + " nonce=" + nonce + " from=" + from
                + (success ? " OK" : " REVERTED" + (revertReason == null ? "" : " (" + revertReason + ")"))
                + " gas=" + gasUsed + "]";
    }
}
