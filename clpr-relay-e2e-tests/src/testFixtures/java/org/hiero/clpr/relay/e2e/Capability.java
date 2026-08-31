// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e;

/**
 * An environment capability an {@link E2ETest} can require. The active {@link E2EProfile} decides
 * which are offered; a test requiring an unavailable capability is skipped.
 *
 * <p>This is the mechanism that keeps one suite runnable in three very different places: a
 * developer laptop, a 4-vCPU PR runner, and a nightly on a big box.
 */
public enum Capability {

    /**
     * More than one QBFT validator per network.
     *
     * <p>Rarely usable: {@code QBFTVerifier} reverts with {@code MultiValidatorNotSupported()}
     * unless the epoch header carries exactly one validator, so a multi-validator chain cannot have
     * its bundles verified by a peer. Only for tests that stay within one chain.
     */
    MULTI_VALIDATOR_QBFT,

    /**
     * Load-test dimensions: many containers and a high transaction rate. Excluded from the PR
     * pipeline because 2 chains x 4 nodes + 11 endpoints does not fit a 4-vCPU / 16 GB runner.
     */
    HIGH_LOAD,

    /**
     * Docker network manipulation (disconnect / reconnect a container) for peer-unreachable
     * scenarios.
     */
    NETWORK_PARTITION,

    /**
     * The test leaves a channel permanently unusable and must therefore own its environment
     * exclusively.
     *
     * <p>The concrete case today is a message backlog larger than the relay's
     * {@code maxMessagesPerBundle}: the relay bundles only the first batch but carries a running
     * hash for the whole queue, the peer rejects every bundle, and the channel never advances again
     * (a known upstream limitation). Nothing recovers it short of a new
     * deployment.
     */
    DESTRUCTIVE
}
