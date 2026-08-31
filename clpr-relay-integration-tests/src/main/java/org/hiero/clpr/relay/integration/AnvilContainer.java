// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A {@link GenericContainer} wrapper for running Foundry's Anvil dev node. The container
 * exposes an HTTP JSON-RPC endpoint (see {@link #jsonRpcUrl()}) and auto-mines blocks
 * at a fixed interval.
 *
 * <p>When invoked without a mnemonic, Anvil always produces the same deterministic
 * set of 10 dev accounts. Account 0 is exposed here as {@link #DEV_ADDRESS} with
 * private key {@link #DEV_PRIVATE_KEY}. These values are well-known Foundry test
 * keys and must not be used on any real network. Integration tests sign every
 * transaction and endpoint message client-side with this key, so no node-side wallet
 * unlocking is needed.
 */
public final class AnvilContainer extends GenericContainer<AnvilContainer> {

    /** Well-known Anvil dev account 0 private key. Test use only. */
    public static final String DEV_PRIVATE_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /** Well-known Anvil dev account 0 address (lowercase, 0x-prefixed). */
    public static final String DEV_ADDRESS = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";

    /**
     * Well-known Anvil dev account 1 private key. Test use only. Tests that run two relays
     * against the same Anvil instance use account 1 for the second relay so the two sides
     * submit from independent nonces and don't race the shared dev-account mempool.
     */
    public static final String DEV_PRIVATE_KEY_2 = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";

    /**
     * Well-known Anvil dev account 2 private key. Test use only. Single-side tests give the
     * relay-under-test this account so its on-chain submissions (e.g. submitBundle) draw from a
     * nonce sequence independent of the test harness's account 0, which the harness consumes via
     * sendMessage / closeChannel. Sharing one account makes the two submitters' cached nonces
     * collide and surface as {@code nonce too low}.
     */
    public static final String DEV_PRIVATE_KEY_3 = "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a";

    /** The chain id used by Anvil when invoked with {@code --chain-id 1337}. */
    public static final long CHAIN_ID = 1337L;

    private static final int JSON_RPC_PORT = 8545;

    /**
     * Create a new Anvil container.
     *
     * <p>The image is Foundry's official build, which ships Anvil. We run it
     * with a fixed chain id of 1337, binding to all interfaces so Testcontainers'
     * port mapping works, and with a one-second block time to keep tests responsive
     * without requiring an explicit mine RPC call per tx.
     */
    public AnvilContainer() {
        super("ghcr.io/foundry-rs/foundry:v1.7.1");
        withExposedPorts(JSON_RPC_PORT);
        // The Foundry image's entrypoint is `["/bin/sh", "-c"]`, so CMD must be a single
        // argv slot containing the whole shell command. Testcontainers' `withCommand(String)`
        // splits on whitespace (turning our flags into positional args to `sh -c anvil`),
        // which caused anvil to silently fall back to its default 127.0.0.1 bind.
        // Pass a one-element array via the varargs overload to preserve the full string.
        // Cancun hardfork enables EIP-1559 and EIP-4844 features we rely on. ClprService no
        // longer inlines its module deploys (see clpr-smart-contracts PR #35), so the
        // EIP-3860 initcode cap is satisfied without disabling code-size limits.
        // --slots-in-an-epoch 1 makes `finalized` resolve to roughly `latest - 2` instead
        // of the default `latest - 64`. Without this, on fast runners the test-class setup
        // finishes before block 64 is mined, so any eth_call at the `finalized` tag clamps
        // to block 0 (pre-deploy) and returns 0x — silently masking the deployed contract.
        setCommand(new String[] {
            "anvil --host 0.0.0.0 --port " + JSON_RPC_PORT + " --chain-id " + CHAIN_ID
                    + " --block-time 1 --slots-in-an-epoch 1 --hardfork cancun"
        });
        // Poll JSON-RPC with a POST until anvil answers 200. Log-line waits fire a hair
        // before the HTTP listener actually accepts connections, and forListeningPort races
        // docker-proxy's bind which is up before anvil itself. An empty POST returns a
        // proper JSON-RPC "invalid request" (HTTP 200) once anvil is live.
        waitingFor(Wait.forHttp("/")
                .forPort(JSON_RPC_PORT)
                .withMethod("POST")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /**
     * The full URL (including scheme, host, and mapped port) where the Anvil JSON-RPC
     * endpoint can be reached.
     */
    public String jsonRpcUrl() {
        return "http://" + getHost() + ":" + getMappedPort(JSON_RPC_PORT);
    }
}
