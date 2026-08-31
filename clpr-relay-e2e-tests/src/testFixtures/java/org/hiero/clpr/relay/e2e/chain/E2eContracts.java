// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

import org.hiero.clpr.relay.integration.DeployedContracts;

/**
 * The CLPR contract set deployed on one chain for an E2E run.
 *
 * <p>The distinguishing piece is {@code qbftVerifier}: the <b>real</b> {@code QBFTVerifier}, which
 * performs full cryptographic verification (QBFT committed seals, Merkle account and storage proofs,
 * code-hash check). The existing integration suite instead deploys {@code StubClprVerifier} and
 * injects a re-encoding submitter into the in-process relay so the stub accepts the proofs. A
 * containerized endpoint has no such seam, so the E2E path must satisfy the production verifier —
 * which is the whole point of running it this way.
 *
 * @param core the ClprService plus the mock verifier/app/connector set deployed by the shared
 *     {@code ContractDeployer}; its {@code clprServiceAddress} is the service every channel uses
 * @param qbftVerifier the real {@code QBFTVerifier}, constructed with one required committed seal
 *     (the only shape it accepts — see {@link BesuNetworkSpec#validatorCount()})
 * @param e2eApplication the {@code E2EApplication} target that inbound messages are dispatched to
 */
public record E2eContracts(DeployedContracts core, String qbftVerifier, String e2eApplication) {

    /** @return the ClprService address for this chain. */
    public String clprService() {
        return core.clprServiceAddress();
    }
}
