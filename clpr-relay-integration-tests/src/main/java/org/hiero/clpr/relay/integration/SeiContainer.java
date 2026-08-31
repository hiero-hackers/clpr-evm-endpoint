// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A {@link GenericContainer} wrapper for running a Sei devnet in a single container.
 *
 * <p><b>It is a 3-node cluster, not a single node.</b> Sei v6.5.2's block-sync reactor only
 * hands off to consensus once a node has at least two P2P peers, so a lone node never produces
 * blocks. {@code sei-init.sh} therefore runs one validator (which produces and signs every
 * block — a single-validator genesis keeps light-client proofs simple) plus two non-validator
 * full nodes that exist only to satisfy that peer requirement. Only the validator's endpoints
 * are exposed; the helper nodes use offset ports internally.
 *
 * <p>The container exposes:
 * <ul>
 *   <li>port {@value #EVM_RPC_PORT} — EVM JSON-RPC (Ethereum-compatible, accessed via
 *       {@link #evmRpcUrl()})</li>
 *   <li>port {@value #COMETBFT_RPC_PORT} — CometBFT RPC (accessed via
 *       {@link #cometBftRpcUrl()})</li>
 * </ul>
 *
 * <p>Startup is handled by the {@code sei-init.sh} classpath resource, which initialises the
 * shared genesis, imports the {@link #DEV_PRIVATE_KEY well-known dev key}, wires the peer mesh,
 * and starts all three nodes (the two peers in the background, the validator in the foreground).
 *
 * <p>The dev private key and its corresponding EVM address are the same as
 * {@code AnvilContainer.DEV_PRIVATE_KEY} / {@code AnvilContainer.DEV_ADDRESS}, both derived from
 * the canonical Foundry / Hardhat test mnemonic
 * {@code "test test test test test test test test test test test junk"}.
 * Tests that also spin up Anvil can therefore reuse the same {@link org.hiero.clpr.relay.evm.EthSigner}
 * instance to sign against both chains.
 *
 * <p><b>Image pin:</b> pinned to {@code ghcr.io/sei-protocol/sei:v6.5.2}. Bump the tag after
 * verifying that {@code sei-init.sh} still works with the new version's CLI interface, that the
 * &gt;=2-peer block-sync constraint still holds (otherwise the cluster size may change), and that
 * {@link #EVM_CHAIN_ID} still matches the node's reported {@code eth_chainId}.
 */
public final class SeiContainer extends GenericContainer<SeiContainer> {

    /**
     * Well-known Sei dev private key — the same as {@code AnvilContainer.DEV_PRIVATE_KEY}.
     * Derived from the Foundry / Hardhat test mnemonic. Test use only; never use on a real network.
     */
    public static final String DEV_PRIVATE_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /**
     * EVM address corresponding to {@link #DEV_PRIVATE_KEY}.
     * Same as {@code AnvilContainer.DEV_ADDRESS}.
     */
    public static final String DEV_ADDRESS = "0xf39Fd6e51aad88F6f4ce6aB8827279cffFb92266";

    /**
     * EVM chain ID of the Sei devnet, as reported by {@code eth_chainId} / {@code net_version}.
     *
     * <p>Sei derives the EVM chain ID from the CometBFT chain-id. For the {@code sei-local}
     * chain-id that {@code sei-init.sh} configures, the node reports {@code 713714} (0xAE3F2) —
     * <b>not</b> the Sei mainnet value (pacific-1 → 1329). {@link SeiTxSubmitter} signs EIP-155
     * transactions with this value, so it MUST match the running node or every transaction is
     * rejected with an invalid-chain-id error. If you change {@code SEI_CHAIN_ID} in
     * {@code sei-init.sh}, re-derive this constant from the new node's {@code eth_chainId}.
     */
    public static final long EVM_CHAIN_ID = 713714L;

    static final int EVM_RPC_PORT = 8545;
    static final int COMETBFT_RPC_PORT = 26657;

    private static final String INIT_SCRIPT_PATH = "/sei-init.sh";

    /**
     * Create a new Sei devnet container.
     */
    public SeiContainer() {
        super("ghcr.io/sei-protocol/sei:v6.5.2");
        withExposedPorts(EVM_RPC_PORT, COMETBFT_RPC_PORT);
        withCopyToContainer(MountableFile.forClasspathResource("sei-init.sh", 0755), INIT_SCRIPT_PATH);
        // v6+ sets seid as ENTRYPOINT; override it so the init script runs under bash directly.
        withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/bash"));
        withCommand(INIT_SCRIPT_PATH);
        // /status returns HTTP 200 immediately — even while the validator is still wedged in
        // block-sync at height 0 — so a bare status-code wait would report "ready" before the
        // chain is usable. Wait until the validator has left block-sync and is committing blocks,
        // which is exactly when sync_info.catching_up flips to false (the EVM JSON-RPC listener
        // opens at the same point). Sei v6.5.2's RPC emits this field without spaces.
        waitingFor(Wait.forHttp("/status")
                .forPort(COMETBFT_RPC_PORT)
                .forResponsePredicate(body -> body.contains("\"catching_up\":false"))
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * The full URL of the EVM JSON-RPC endpoint (Ethereum-compatible).
     */
    public String evmRpcUrl() {
        return "http://" + getHost() + ":" + getMappedPort(EVM_RPC_PORT);
    }

    /**
     * The full URL of the CometBFT RPC endpoint.
     */
    public String cometBftRpcUrl() {
        return "http://" + getHost() + ":" + getMappedPort(COMETBFT_RPC_PORT);
    }
}
