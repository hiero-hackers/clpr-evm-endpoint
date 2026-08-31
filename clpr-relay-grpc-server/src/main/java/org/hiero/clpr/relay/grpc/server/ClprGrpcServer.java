// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import com.hedera.pbj.grpc.helidon.PbjRouting;
import com.hedera.pbj.grpc.helidon.config.PbjConfig;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsClientAuth;
import io.helidon.webserver.ListenerConfig;
import io.helidon.webserver.WebServer;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Helidon-based gRPC server hosting the CLPR endpoint service on two listeners:
 *
 * <ul>
 *   <li><b>sync</b> ({@code syncPort}, Helidon's default socket) — the data plane. Plaintext by default;
 *       <b>mandatory mTLS</b> ({@code clientAuth=REQUIRED}) when {@code tlsEnabled}, in which case
 *       {@code leaf} supplies the endpoint's pre-generated leaf certificate, and the dialer's client
 *       leaf must chain to a CA in the on-chain roster.</li>
 *   <li><b>info</b> ({@code infoPort}, a named socket) — <b>always plaintext</b>. Hosts
 *       {@code getLedgerConfiguration}, which returns chain-verifiable public data, so it is never
 *       put behind TLS.</li>
 * </ul>
 */
public class ClprGrpcServer {

    private static final Logger LOG = Loggers.getLogger(ClprGrpcServer.class);

    /** Named socket for the always-plaintext info listener (the sync listener holds the default socket). */
    private static final String INFO_SOCKET = "info";

    /**
     * The sync and info listener ports plus the optional mTLS key manager for the sync listener. Both
     * ports are always served (enforced by the config loader). The sync listener is mandatory mTLS when
     * {@code keyManager} is non-null, plaintext otherwise; the info listener is always plaintext.
     *
     * @param syncPort   sync (data-plane) listener port
     * @param infoPort   info (metadata) listener port; always plaintext
     * @param keyManager the endpoint's dynamic leaf key manager; non-null enables mandatory mTLS on the
     *                   sync listener, {@code null} means plaintext
     */
    public record Listeners(
            int syncPort, int infoPort, @Nullable LeafKeyManager keyManager) {
        public boolean tlsEnabled() {
            return keyManager != null;
        }
    }

    private final Listeners listeners;
    private final int maxMessageSize;
    private final String instanceName;

    private final PeerEndpointTlsRegistry tlsRegistry;
    private final ClprSyncService syncService;
    private final ClprInfoService infoService;

    private volatile WebServer server;
    private volatile Tls secureSyncTls;

    /**
     * Creates a new gRPC server.
     *
     * @param listeners                     the sync/info listener ports and optional sync mTLS leaf
     * @param maxMessageSize                maximum inbound gRPC message size in bytes; overrides PBJ's 10 KiB default
     * @param syncHandler                   the handler for sync RPCs
     * @param tlsRegistry                   global registry of peer TLS CA certificates; used by the
     *                                      mandatory-mTLS trust manager and {@code sync} peer-identity resolution
     * @param getLedgerConfigurationHandler the handler for {@code getLedgerConfiguration} RPCs
     * @param instanceName                  free-form label used in the server name and logs
     */
    public ClprGrpcServer(
            final Listeners listeners,
            final int maxMessageSize,
            final ClprSyncHandler syncHandler,
            final PeerEndpointTlsRegistry tlsRegistry,
            final GetLedgerConfigurationHandler getLedgerConfigurationHandler,
            final String instanceName) {
        this.listeners = listeners;
        this.maxMessageSize = maxMessageSize;
        this.instanceName = instanceName;
        this.tlsRegistry = tlsRegistry;
        this.syncService = new ClprSyncService(syncHandler, tlsRegistry);
        this.infoService = new ClprInfoService(getLedgerConfigurationHandler);
    }

    /**
     * Starts the gRPC server. Blocks until the server is ready to accept connections.
     */
    public void start() {
        if (listeners.keyManager() != null) {
            secureSyncTls = buildSecureSyncTls(listeners.keyManager());
        }

        final var builder = WebServer.builder().name(instanceName + " clpr-grpc");
        // Data plane on the default socket; always-plaintext info on a named socket.
        applySync(builder);
        builder.putSocket(INFO_SOCKET, this::applyInfo);

        server = builder.build().start();
        LOG.info(
                "CLPR gRPC server started (sync: {} [{}]; info: {} [plaintext]; maxMessageSize={} bytes)",
                listeners.syncPort(),
                listeners.tlsEnabled() ? "mTLS" : "plaintext",
                listeners.infoPort(),
                maxMessageSize);
    }

    /** Mandatory-mTLS context for the secure sync listener: presents the leaf, validates the roster CA chain. */
    private Tls buildSecureSyncTls(final LeafKeyManager keyManager) {
        final var trustManager = new ServerSideTrustManager(tlsRegistry);
        final var sslContext = ServerTls.serverContext(keyManager, trustManager);
        return Tls.builder()
                .sslContext(sslContext)
                .clientAuth(TlsClientAuth.REQUIRED)
                // Client identity is the roster CA chain, not a hostname; disable endpoint identification.
                .endpointIdentificationAlgorithm(Tls.ENDPOINT_IDENTIFICATION_NONE)
                .build();
    }

    private PbjConfig pbjProtocol() {
        return PbjConfig.builder()
                .name("pbj")
                .maxMessageSizeBytes(maxMessageSize)
                .build();
    }

    /** The sync (data-plane) listener: mTLS when configured, plaintext otherwise. */
    private void applySync(final ListenerConfig.BuilderBase<?, ?> socket) {
        socket.port(listeners.syncPort());
        if (listeners.tlsEnabled()) {
            socket.tls(secureSyncTls);
        }
        socket.addProtocol(pbjProtocol());
        socket.addRouting(PbjRouting.builder().service(syncService));
    }

    /** The info (metadata) listener: always plaintext. */
    private void applyInfo(final ListenerConfig.BuilderBase<?, ?> socket) {
        socket.port(listeners.infoPort());
        socket.addProtocol(pbjProtocol());
        socket.addRouting(PbjRouting.builder().service(infoService));
    }

    /**
     * Stops the gRPC server.
     */
    public void stop() {
        if (server != null) {
            server.stop();
            LOG.info("CLPR gRPC server stopped");
        }
    }

    /**
     * Returns the sync listener's port (the default socket), or {@code -1} if not started.
     *
     * @return the listening port of the sync socket
     */
    public int port() {
        return server != null ? server.port() : -1;
    }

    /**
     * Returns the info listener's port, or {@code -1} if not started.
     *
     * @return the listening port of the info socket
     */
    public int infoPort() {
        return server != null ? server.port(INFO_SOCKET) : -1;
    }

    /**
     * Returns whether the server is currently running.
     *
     * @return {@code true} if the server is running
     */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }
}
