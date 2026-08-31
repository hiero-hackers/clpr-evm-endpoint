// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import org.jspecify.annotations.NonNull;

/**
 * The addresses of the CLPR smart contracts deployed by {@link ContractDeployer}.
 *
 * <p>All addresses are lowercase {@code 0x}-prefixed hex strings.
 */
public record DeployedContracts(
        @NonNull String clprServiceAddress,
        @NonNull String mockVerifierAddress,
        @NonNull String stubVerifierAddress,
        @NonNull String mockAppAddress,
        @NonNull String mockConnectorAddress) {}
