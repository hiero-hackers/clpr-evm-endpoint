// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.clpr.relay.app.ProofType;
import org.hiero.clpr.relay.app.RelayInstance;
import org.hiero.clpr.relay.core.Certs;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.EthSigner;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient;
import org.hiero.clpr.relay.grpc.client.ClprEndpointClient;
import org.hiero.clpr.relay.test.harness.StubPeer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.lang3.tuple.Triple;

/**
 * Base for <b>single-side</b> lifecycle tests over <b>mutual TLS</b>: one real relay against one
 * Anvil-deployed CLPR contract set, with the peer replaced by a TLS-enabled controllable
 * {@link StubPeer} and the chain driven directly by a {@link ContractInteractor}.
 *
 * <p>Replaces the two-relay {@link IntegrationTestBase} for lifecycle cases: half the contract
 * deployments and full control over the peer's bundles. The relay-under-test signs from a dedicated
 * account (account 2) distinct from the harness's account 0, so the relay's on-chain submissions and the
 * harness's sendMessage / closeChannel draw from independent nonce sequences and never collide. The
 * local CLPR Service remains the correctness oracle (it enforces §4.2 verification on every poked bundle).
 *
 * <p>TLS wiring:
 * <ul>
 *   <li>The relay's sync listener is mandatory-mTLS: it holds a leaf minted by CA-A (whose private key
 *       is written to {@link #tempDir} and passed to {@link RelayTestSupport#secureRelayConfig}).</li>
 *   <li>The stub peer is started with a {@link LeafKeyManager} backed by CA-B's private key, so it
 *       presents a leaf signed by CA-B at every TLS handshake — both as a TLS server (for the relay's
 *       outbound dial) and as a TLS client (for {@link #poke} calls into the relay's mTLS listener).</li>
 *   <li>CA-B's DER certificate is published in the stub peer's on-chain roster entry (via
 *       {@link ContractInteractor#configureVerifierConfig}). The relay reads that cert and uses it as
 *       the trust anchor when dialing the stub peer's TLS port.</li>
 * </ul>
 *
 * <p>The relay is started in {@code POKE_ONLY} mode (outbound sync orchestrator not started). Tests
 * that want to exercise the relay's outbound TLS dial to the stub peer can call
 * {@link #restartFullDuplex()} to switch to full-duplex mode.
 */
@Testcontainers
public abstract class OneSidedStubPeerTestBase {

    protected static final String RELAY_HOST = "127.0.0.1";
    private static final BigInteger CONNECTOR_STAKE = BigInteger.valueOf(100_000_000_000_000_000L);

    @Container
    protected static final AnvilContainer ANVIL = new AnvilContainer();

    /**
     * The relay-under-test's signing key (account 2). One key serves both roles the relay holds: EVM
     * transaction signing and endpoint sync-payload signing. It is deliberately distinct from the
     * harness's account 0 and the stub peer's account 1, so the relay's submitBundle transactions and the
     * harness's sendMessage / closeChannel draw from independent nonce sequences and never collide. Per the
     * contract ({@code BundleLogic.submitBundle}) any registered endpoint may submit a bundle for any
     * channel — the submitter need not own the channel — so account 2 only has to register itself as an
     * endpoint.
     */
    protected static final String RELAY_PRIVATE_KEY = AnvilContainer.DEV_PRIVATE_KEY_3;

    /** 64-byte uncompressed secp256k1 public key for the harness's admin/connector account (account 0). */
    protected static final byte[] DEV_PUB_KEY_64 = EthSigner.derivePublicKey(AnvilContainer.DEV_PRIVATE_KEY);

    /** Temporary directory holding the relay's CA private-key file. Injected by JUnit before {@code @BeforeAll}. */
    @TempDir
    static Path tempDir;

    protected static EvmJsonRpcClient rpc;
    protected static AnvilTxSubmitter txSubmitter;
    protected static DeployedContracts contracts;
    protected static ContractInteractor interactor;
    protected static byte[] channelId;
    protected static byte[] connectorId;
    protected static RelayInstance relay;
    protected static StubPeer peer;
    protected static int relayPort;

    /** Path to the relay's CA private key on disk; reused by {@link #restartFullDuplex()}. */
    protected static Path relayKeyPath;

    private static final Metrics metrics = new SimpleMetrics();

    @BeforeAll
    static void setupSecureOneSided() throws Exception {
        // Write the relay's CA-A private key to disk so secureRelayConfig can load it.
        relayKeyPath = tempDir.resolve("relay-ca-a.der");
        Files.write(relayKeyPath, CertFixtures.CA_A_KEY_DER);

        rpc = new TestEvmJsonRpcClient(ANVIL.jsonRpcUrl());
        final var devSigner = new EthSigner(AnvilContainer.DEV_PRIVATE_KEY);
        txSubmitter = new AnvilTxSubmitter(rpc, devSigner, AnvilContainer.CHAIN_ID);
        contracts = new ContractDeployer(rpc, txSubmitter).deploy();
        interactor = new ContractInteractor(rpc, txSubmitter, devSigner, DEV_PUB_KEY_64, contracts);

        // The stub peer presents a TLS leaf signed by CA-B and requires connecting clients to present
        // a leaf that chains to CA-A (the relay's CA). Duration.ZERO disables rotation so the leaf is
        // valid for 10 years — no rotation churn during a short test run.
        peer = StubPeer.start(
                new LeafKeyManager(Certs.parsePrivateKey(CertFixtures.CA_B_KEY_DER), Duration.ZERO, metrics),
                CertFixtures.CA_A_CERT_DER);

        relayPort = RelayTestSupport.findFreePort();
        final byte[] serviceAddr = AbiCodec.fromHex(contracts.clprServiceAddress());

        // The service deploys default-disabled; initialize + setClprEnabled(true) via
        // bootstrapDefaults before any mutating op, otherwise the calls below revert with ClprDisabled().
        interactor.bootstrapDefaults(serviceAddr);

        // Publish CA-B's DER in the stub peer's on-chain roster entry. The relay reads that cert and
        // uses it as the trust anchor when dialing the stub peer's TLS port (spec §2.4.2). Using the
        // 6-argument overload so the peer's tls_certificate field is non-empty, which triggers the
        // relay's ClprEndpointClient to build a mutual-TLS channel rather than a plaintext one.
        interactor.configureVerifierConfig(
                contracts.stubVerifierAddress(),
                "eip155:1337",
                serviceAddr,
                1000,
                peer.port(),
                CertFixtures.CA_B_CERT_DER);

        final byte[] pong = {'P', 'O', 'N', 'G'};
        interactor.configureAppResponse(contracts.mockAppAddress(), pong);

        final byte[] salt = new byte[32];
        channelId = interactor.registerActiveChannel("eip155:1337", salt, contracts.stubVerifierAddress());

        RelayTestSupport.sendEthAndWait(txSubmitter, rpc, contracts.mockConnectorAddress(), 1_000_000_000_000_000_000L);
        connectorId = interactor.registerConnector(
                channelId, salt, contracts.mockConnectorAddress(), AnvilContainer.DEV_ADDRESS, CONNECTOR_STAKE);

        final String channelIdHex = "0x" + AbiCodec.toHexNoPrefix(channelId);
        relay = RelayTestSupport.buildRelay(
                RelayTestSupport.secureRelayConfig(
                        ANVIL.jsonRpcUrl(),
                        relayPort,
                        relayKeyPath.toString(),
                        RELAY_PRIVATE_KEY,
                        List.of(Triple.of(channelIdHex, contracts.clprServiceAddress(), ProofType.Hiero))),
                "A");
        // POKE_ONLY: start the inbound gRPC server + state listener, but NOT the outbound sync loop.
        relay.start(RelayInstance.StartOptions.inboundOnly());
    }

    @AfterAll
    static void teardownSecureOneSided() {
        if (relay != null) {
            relay.stop();
        }
        if (peer != null) {
            peer.close();
        }
    }

    /**
     * Inject a crafted bundle into the relay-under-test over mutual TLS and return the relay's
     * reciprocal reply. The stub peer presents its CA-B-signed leaf as the client certificate; the
     * relay's CA-A certificate is used as the server trust anchor.
     *
     * @param bundlePayload the crafted bundle (e.g. via {@code StubBundles})
     * @return the relay's reciprocal sync reply
     * @throws Exception if the gRPC call or TLS handshake fails
     */
    protected static ClprSyncPayload poke(final Bytes bundlePayload) throws Exception {
        final Bytes connId = Bytes.wrap(channelId);
        return peer.pokeSync(
                RELAY_HOST,
                relay.grpcPort(),
                ClprSyncPayload.newBuilder()
                        .channelId(connId)
                        .bundlePayload(bundlePayload)
                        .build(),
                Bytes.wrap(CertFixtures.CA_A_CERT_DER));
    }

    /** The peer-side target application address for outbound {@code sendMessage} calls (a placeholder here). */
    protected static byte[] targetApp() {
        return AbiCodec.fromHex(contracts.mockAppAddress());
    }

    /**
     * Stop the relay and restart it in FULL_DUPLEX mode using the supplied CA key and leaf validity.
     * Callers pass an alternative key path to test CA-mismatch rejection, or a short validity to
     * exercise leaf rotation before the first dial.
     *
     * @param keyPath                 path to the CA PKCS#8 private key to use for the new relay instance
     * @param leafCertValiditySeconds seconds between leaf re-mints for the new relay instance
     */
    protected static void restartFullDuplex(final String keyPath, final long leafCertValiditySeconds) {
        relay.stop();
        final String channelIdHex = "0x" + AbiCodec.toHexNoPrefix(channelId);
        relay = RelayTestSupport.buildRelay(
                RelayTestSupport.secureRelayConfig(
                        ANVIL.jsonRpcUrl(),
                        relayPort,
                        keyPath,
                        leafCertValiditySeconds,
                        RELAY_PRIVATE_KEY,
                        List.of(Triple.of(channelIdHex, contracts.clprServiceAddress(), ProofType.Hiero))),
                "A");
        relay.start(RelayInstance.StartOptions.full());
    }

    /**
     * Stop the relay and restart it in FULL_DUPLEX mode (outbound sync orchestrator running), reusing
     * the same gRPC port and the same secure mTLS configuration. Used by tests that exercise the
     * relay's outbound TLS dial to the stub peer — the relay's {@link ClprEndpointClient} reads the
     * non-empty on-chain
     * {@code tls_certificate} from the peer's roster entry and builds a mutual-TLS channel.
     */
    protected static void restartFullDuplex() {
        restartFullDuplex(relayKeyPath.toString(), 86400L);
    }
}
