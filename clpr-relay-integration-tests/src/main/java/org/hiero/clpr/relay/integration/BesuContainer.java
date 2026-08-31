// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A {@link GenericContainer} wrapper for running Hyperledger Besu in development mode. The
 * container exposes an HTTP JSON-RPC endpoint (see {@link #jsonRpcUrl()}) and auto-mines a
 * new block for each submitted transaction.
 *
 * <p>The dev-mode genesis funds {@link #DEV_ADDRESS} with a large balance. The corresponding
 * private key is {@link #DEV_PRIVATE_KEY} — this is the well-known Besu dev key documented in
 * the Besu quickstart guide and must not be used on any real network.
 */
public final class BesuContainer extends GenericContainer<BesuContainer> {

    /** Well-known Besu dev private key (documented in Besu docs). Test use only. */
    public static final String DEV_PRIVATE_KEY = "8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63";

    /** Well-known Besu dev account address. */
    public static final String DEV_ADDRESS = "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73";

    /** The chain id used by Besu in {@code --network=dev} mode. */
    public static final long CHAIN_ID = 1337L;

    private static final int JSON_RPC_PORT = 8545;

    /**
     * Create a new Besu container.
     *
     * <p>Pinned to {@code hyperledger/besu:25.7.0} because Besu 26.x removed the
     * {@code --miner-enabled} / {@code --miner-coinbase} command-line flags (PoW
     * deprecation), which the test harness relies on to auto-mine every tx and
     * credit mining rewards to the well-known dev account. Upgrading to 26.x would
     * require a configuration-file based setup and different consensus; 25.7 keeps
     * the tests simple.
     */
    public BesuContainer() {
        super("hyperledger/besu:25.7.0");
        withExposedPorts(JSON_RPC_PORT);
        withCommand(
                "--genesis-file=/genesis.json",
                "--miner-enabled",
                "--miner-coinbase=" + DEV_ADDRESS,
                "--rpc-http-enabled",
                "--rpc-http-host=0.0.0.0",
                "--rpc-http-port=" + JSON_RPC_PORT,
                "--rpc-http-api=ETH,NET,WEB3,DEBUG,MINER,ADMIN,QBFT",
                "--host-allowlist=*",
                "--min-gas-price=0",
                "--data-path=/tmp/besu");
        withCopyToContainer(MountableFile.forClasspathResource("besu-genesis.json"), "/genesis.json");
        // Besu returns HTTP 415 to GET on the JSON-RPC endpoint (it expects POST with a body),
        // so we can't use the built-in HTTP wait strategy with a predicate. Wait on the log
        // line emitted once the JSON-RPC service is listening.
        waitingFor(Wait.forLogMessage(".*JSON-RPC service started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * The full URL (including scheme, host, and mapped port) where the Besu JSON-RPC endpoint
     * can be reached.
     */
    public String jsonRpcUrl() {
        return "http://" + getHost() + ":" + getMappedPort(JSON_RPC_PORT);
    }

    public String validatorAddress() {
        // Single validator hardcoded in extraData of besu-genesis.json
        return "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73";
    }
}
