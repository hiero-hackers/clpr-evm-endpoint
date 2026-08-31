// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprChannel;

/**
 * Submits CLPR bundles to the EVM smart contract as on-chain transactions.
 */
public interface TransactionSubmitter {

    /**
     * Enqueue a verified bundle for on-chain submission on behalf of the given channel.
     *
     * <p>The bundle is encoded into the proof-bytes format the channel's on-chain verifier
     * expects and submitted as a transaction. The call is <em>not</em> synchronous: it enqueues the
     * bundle for the signing account's serial submitter and returns immediately. The outcome
     * (preview-skip, submitted, reverted, or failed) is handled internally by that submitter and
     * surfaced through metrics; it is never returned or thrown to the caller.
     *
     * @param channel the CLPR channel the bundle belongs to; supplies the channel id and
     *                   the local queue/lifecycle state a submitter may use to decide whether the
     *                   bundle still needs sending
     * @param verified the cryptographically verified bundle to submit
     */
    void submitBundle(ClprChannel channel, ParsedBundle verified);
}
