// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A Foundry contract compilation artifact: the ABI JSON (as a string) plus the deployment
 * bytecode (as a hex string with or without {@code 0x} prefix).
 *
 * <p>Artifacts are loaded from the {@code out/} directory of the {@code clpr-smart-contracts}
 * repository, which is expected to be checked out as a sibling of this repository.
 */
public record ContractArtifact(String abi, String bytecode) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Default directory containing Foundry artifacts — a sibling of this repository.
     *
     * <p>Resolved relative to {@code user.dir} at the time of classloading, but because
     * {@code user.dir} varies between Gradle invocations (root project vs. subproject),
     * {@link #loadByName(String)} probes a small list of candidate roots before giving up.
     */
    private static final Path CONTRACTS_OUT = Path.of("../clpr-smart-contracts/out");

    /** Candidate locations for the smart-contracts {@code out/} directory. */
    private static final Path[] CANDIDATE_OUT_DIRS = new Path[] {
        CONTRACTS_OUT, // cwd = clpr-relay-integration-tests (gradle subproject default)
        Path.of("../../clpr-smart-contracts/out"), // cwd = repo root
        Path.of("clpr-smart-contracts/out") // cwd = parent of both repos
    };

    /**
     * Load a contract artifact from an explicit path to a Foundry JSON file.
     *
     * @param artifactPath absolute or relative path to a {@code *.json} produced by {@code forge build}
     * @return the parsed artifact
     * @throws IOException if the file cannot be read or parsed
     */
    public static ContractArtifact load(final Path artifactPath) throws IOException {
        final JsonNode root = MAPPER.readTree(artifactPath.toFile());
        final String abi = root.get("abi").toString();
        final String bytecode = root.get("bytecode").get("object").asText();
        return new ContractArtifact(abi, bytecode);
    }

    /**
     * Load a contract artifact by simple contract name from the default contracts-out directory.
     * For example, {@code loadByName("ClprService")} loads
     * {@code ../clpr-smart-contracts/out/ClprService.sol/ClprService.json}.
     *
     * @param name the contract name without {@code .sol}
     * @return the parsed artifact
     * @throws IOException if the file cannot be read or parsed
     */
    public static ContractArtifact loadByName(final String name) throws IOException {
        for (final Path root : CANDIDATE_OUT_DIRS) {
            final Path candidate = root.resolve(name + ".sol").resolve(name + ".json");
            if (candidate.toFile().isFile()) {
                return load(candidate);
            }
        }
        throw new IOException("Could not locate Foundry artifact for '" + name
                + "'. Checked (relative to cwd=" + System.getProperty("user.dir") + "): "
                + java.util.Arrays.toString(CANDIDATE_OUT_DIRS));
    }
}
