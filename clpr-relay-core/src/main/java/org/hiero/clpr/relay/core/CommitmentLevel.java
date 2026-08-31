// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

/**
 * Represents the level of commitment (finality) required when reading EVM state.
 *
 * <p>These map directly to the JSON-RPC block tags used by Ethereum-compatible nodes.
 */
public enum CommitmentLevel {
    /** The most recently finalized block (highest safety guarantee). */
    FINALIZED,
    /** A block considered safe under the consensus protocol. */
    SAFE,
    /** The most recent block (lowest latency, lowest safety guarantee). */
    LATEST;

    /**
     * Returns the JSON-RPC block tag string for this commitment level.
     *
     * @return lowercase name suitable for use as a JSON-RPC {@code blockTag} parameter
     */
    public String toBlockTag() {
        return name().toLowerCase();
    }
}
