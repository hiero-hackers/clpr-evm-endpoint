// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.clpr.relay.test.harness.RelayAssertions.assertMetric;

import java.util.List;
import org.hiero.clpr.relay.app.ProofType;
import org.hiero.clpr.relay.app.RelayInstance;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient;
import org.hiero.clpr.relay.test.harness.StubPeer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.Triple;

/**
 * A channel completed against a valid, version-1 {@code ClprEndpointManifest} that carries
 * <em>no endpoints</em> is a legitimate on-ledger state (jnels124 review scenario #5). This test
 * verifies both halves of that contract:
 *
 * <ol>
 *   <li>{@code completeChannel} succeeds against an empty peer manifest — the resulting
 *       channel is ACTIVE.</li>
 *   <li>The relay boots cleanly against it: {@code ClprChannelHandler}'s startup
 *       {@code readPeerEndpointManifest} finds an empty manifest, caches no peers, and does not
 *       throw. Because the listener skips {@code evm.listener.manifest.refreshed} for an empty
 *       manifest, the counter stays at zero (contrast {@link PeerManifestRefreshStubPeerTest}, where
 *       a populated manifest drives it positive at startup).</li>
 * </ol>
 *
 * <p>The empty manifest is produced by seeding one endpoint via {@code configureVerifierConfig}
 * and then overwriting it with {@link ContractInteractor#setEmptySeedEndpoints} before
 * {@code registerActiveChannel}, so the channel is completed against the empty manifest.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
@Testcontainers
class EmptyPeerManifestStubPeerTest {

    @Container
    static final AnvilContainer ANVIL = new AnvilContainer();

    static EvmJsonRpcClient rpc;
    static ContractInteractor interactor;
    static DeployedContracts contracts;
    static byte[] channelId;
    static RelayInstance relay;
    static StubPeer peer;
    static int relayPort;

    @BeforeAll
    static void setup() throws Exception {
        rpc = new TestEvmJsonRpcClient(ANVIL.jsonRpcUrl());
        final var devSigner = new EthSigner(AnvilContainer.DEV_PRIVATE_KEY);
        final var txSubmitter = new AnvilTxSubmitter(rpc, devSigner, AnvilContainer.CHAIN_ID);
        final byte[] devPubKey64 = EthSigner.derivePublicKey(AnvilContainer.DEV_PRIVATE_KEY);
        contracts = new ContractDeployer(rpc, txSubmitter).deploy();
        interactor = new ContractInteractor(rpc, txSubmitter, devSigner, devPubKey64, contracts);

        peer = StubPeer.start();
        peer.respondEmpty();

        relayPort = RelayTestSupport.findFreePort();
        final byte[] serviceAddr = AbiCodec.fromHex(contracts.clprServiceAddress());

        // The service deploys default-disabled; initialize + setClprEnabled(true) via bootstrapDefaults
        // before any mutating op, otherwise the calls below revert with ClprDisabled().
        interactor.bootstrapDefaults(serviceAddr);
        interactor.configureVerifierConfig(
                contracts.mockVerifierAddress(), "eip155:1337", serviceAddr, 1000, peer.port());
        // Overwrite the seeded endpoint with an empty (but valid, version-1) manifest, so the
        // channel is completed against a peer manifest with no endpoints.
        interactor.setEmptySeedEndpoints(contracts.mockVerifierAddress());
        interactor.configureAppResponse(contracts.mockAppAddress(), new byte[] {0x50, 0x4F, 0x4E, 0x47});

        final byte[] salt = new byte[32];
        channelId = interactor.registerActiveChannel("eip155:1337", salt, contracts.mockVerifierAddress());

        final String channelIdHex = "0x" + AbiCodec.toHexNoPrefix(channelId);
        relay = RelayTestSupport.buildRelay(
                RelayTestSupport.relayConfig(
                        ANVIL.jsonRpcUrl(),
                        relayPort,
                        AnvilContainer.DEV_PRIVATE_KEY_3,
                        List.of(Triple.of(channelIdHex, contracts.clprServiceAddress(), ProofType.Hiero))),
                "A");
        relay.start(RelayInstance.StartOptions.inboundOnly());
    }

    @AfterAll
    static void teardown() {
        if (relay != null) relay.stop();
        if (peer != null) peer.close();
    }

    @Test
    void emptyPeerManifestAtChannelCreation_completesAndBootsWithoutRefresh() {
        // (1) completeChannel succeeded against the empty manifest → channel is ACTIVE.
        final ContractInteractor.ChannelState state = interactor.requireChannelState(channelId);
        assertThat(state.status()).as("channel status ACTIVE").isEqualTo(1);

        // (2) The relay booted cleanly against the empty peer manifest.
        assertThat(relay.isRunning()).isTrue();

        // The startup manifest read found no endpoints, so no peer was cached and the listener never
        // incremented the refresh counter (it skips it for an empty manifest). This value is invariant
        // at zero for an empty manifest, so the assertion is not racy.
        assertMetric(relay.metrics(), "evm.listener", "manifest.refreshed").isZero();
    }
}
