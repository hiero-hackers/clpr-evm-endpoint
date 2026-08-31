// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.test.harness;

import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.grpc.helidon.PbjRouting;
import com.hedera.pbj.grpc.helidon.config.PbjConfig;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.Pipelines;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsClientAuth;
import io.helidon.webserver.WebServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.grpc.client.ClprEndpointClient;
import org.jspecify.annotations.Nullable;

/**
 * A controllable CLPR peer endpoint for single-side integration tests.
 *
 * <p>Hosts a real Helidon + PBJ gRPC server (the same stack as the production
 * {@code ClprGrpcServer}), so the endpoint-under-test's real {@code ClprEndpointClient} talks to it
 * over real HTTP/2. Two directions:
 *
 * <ul>
 *   <li><b>Server (responder):</b> answers the endpoint's outbound {@code sync} calls. The response is
 *       decided synchronously by the configured policy ({@link #respondEmpty()} /
 *       {@link #respondWith(ClprSyncPayload)} / {@link #onSync(Function)}) — the <em>only</em> place a
 *       reply can depend on the request. Every inbound payload is recorded for assertions.</li>
 *   <li><b>Client (injector):</b> {@link #pokeSync} pushes a crafted payload <em>into</em> the
 *       endpoint-under-test and returns its reciprocal reply.</li>
 * </ul>
 *
 * <p>{@link #awaitSync} is a read-only tap on the recorded request stream; it does not shape replies.
 * The standing responder must always return promptly (the endpoint's client read timeout is ~10s).
 */
public final class StubPeer implements AutoCloseable {

    private static final int DEFAULT_MAX_MESSAGE_SIZE = 1 << 20;

    private final WebServer server;
    private final ClprEndpointClient client;
    private final BlockingQueue<ClprSyncPayload> received = new LinkedBlockingQueue<>();
    private final AtomicInteger syncCount = new AtomicInteger();

    /** Standing response policy; defaults to an empty-proof (responsive-but-silent) peer. */
    private volatile Function<ClprSyncPayload, ClprSyncPayload> syncResponder = StubPeer::emptyReply;

    private StubPeer(
            final int maxMessageSize,
            @Nullable final LeafKeyManager serverKeyManager,
            @Nullable final byte[] clientCaDer) {
        // When a serverKeyManager is present the stub peer also uses it as its client identity so
        // that pokeSync(host, port, payload, serverCaDer) can present a client certificate against
        // a mandatory-mTLS relay listener.
        this.client = new ClprEndpointClient(maxMessageSize, serverKeyManager);
        final var builder = WebServer.builder()
                .name("stub-peer clpr-grpc")
                .port(0) // ephemeral; read back via port()
                .addProtocol(PbjConfig.builder()
                        .name("pbj")
                        .maxMessageSizeBytes(maxMessageSize)
                        .build())
                .addRouting(PbjRouting.builder().service(new StubService()));
        if (serverKeyManager != null) {
            builder.tls(buildTls(serverKeyManager, clientCaDer));
        }
        this.server = builder.build().start();
    }

    /** Start a stub peer on an ephemeral port with the default max message size. */
    public static StubPeer start() {
        return new StubPeer(DEFAULT_MAX_MESSAGE_SIZE, null, null);
    }

    /** Start a stub peer on an ephemeral port with an explicit max gRPC message size. */
    public static StubPeer start(final int maxMessageSize) {
        return new StubPeer(maxMessageSize, null, null);
    }

    /**
     * Start a TLS-enabled stub peer on an ephemeral port with the default max message size. The server
     * presents the certificate from {@code serverKeyManager}; no client certificate is required (one-way
     * TLS from the connecting peer's perspective).
     */
    public static StubPeer start(final LeafKeyManager serverKeyManager) {
        return new StubPeer(DEFAULT_MAX_MESSAGE_SIZE, serverKeyManager, null);
    }

    /**
     * Start a mutual-TLS stub peer on an ephemeral port with the default max message size. The server
     * presents the certificate from {@code serverKeyManager} and requires every connecting client to
     * present a leaf certificate that chains to {@code clientCaDer}.
     *
     * @param serverKeyManager the key material the stub peer presents as the TLS server
     * @param clientCaDer      DER-encoded CA certificate used to verify the connecting client's leaf
     */
    public static StubPeer start(final LeafKeyManager serverKeyManager, final byte[] clientCaDer) {
        return new StubPeer(DEFAULT_MAX_MESSAGE_SIZE, serverKeyManager, clientCaDer);
    }

    /**
     * Start a TLS-enabled stub peer backed by a static, non-rotating key manager. Unlike
     * {@link #start(LeafKeyManager)}, the certificate returned by {@code serverKm} is never rotated,
     * making this factory suitable for scenarios where the presented certificate must stay fixed —
     * for example an already-expired certificate that {@link LeafKeyManager} would otherwise rotate
     * away on the first TLS handshake.
     *
     * <p>The returned peer has no client TLS identity; {@link #pokeSync(String, int, ClprSyncPayload, Bytes)}
     * dials plaintext and will fail against a mandatory-mTLS listener.
     *
     * @param serverKm the static key manager supplying the server certificate and private key
     */
    public static StubPeer startWithStaticKeyManager(final X509ExtendedKeyManager serverKm) {
        return new StubPeer(DEFAULT_MAX_MESSAGE_SIZE, serverKm);
    }

    private StubPeer(final int maxMessageSize, final X509ExtendedKeyManager staticKeyManager) {
        this.client = new ClprEndpointClient(maxMessageSize, null);
        final var builder = WebServer.builder()
                .name("stub-peer clpr-grpc")
                .port(0)
                .addProtocol(PbjConfig.builder()
                        .name("pbj")
                        .maxMessageSizeBytes(maxMessageSize)
                        .build())
                .addRouting(PbjRouting.builder().service(new StubService()));
        builder.tls(buildTls(staticKeyManager, null));
        this.server = builder.build().start();
    }

    private static Tls buildTls(final X509ExtendedKeyManager serverKeyManager, @Nullable final byte[] clientCaDer) {
        try {
            final SSLContext ctx = SSLContext.getInstance("TLS");
            final TrustManager[] trustManagers = clientCaDer != null ? buildTrustManagers(clientCaDer) : null;
            ctx.init(new KeyManager[] {serverKeyManager}, trustManagers, new SecureRandom());
            return Tls.builder()
                    .sslContext(ctx)
                    .clientAuth(clientCaDer != null ? TlsClientAuth.REQUIRED : TlsClientAuth.NONE)
                    .endpointIdentificationAlgorithm(Tls.ENDPOINT_IDENTIFICATION_NONE)
                    .build();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("failed to build stub-peer TLS context: " + e.getMessage(), e);
        }
    }

    private static TrustManager[] buildTrustManagers(final byte[] caDer) throws GeneralSecurityException {
        final CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final X509Certificate caCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(caDer));
        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (final IOException e) {
            throw new KeyStoreException("failed to initialize client trust store", e);
        }
        ks.setCertificateEntry("ca", caCert);
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    /** The bound (ephemeral) port. */
    public int port() {
        return server.port();
    }

    // ── Response policy (server thread) ───────────────────────────────────────

    /** Answer every outbound sync with an empty-proof response (the default). */
    public StubPeer respondEmpty() {
        this.syncResponder = StubPeer::emptyReply;
        return this;
    }

    /** Answer every outbound sync with a fixed payload. */
    public StubPeer respondWith(final ClprSyncPayload fixed) {
        this.syncResponder = req -> fixed;
        return this;
    }

    /**
     * Answer each outbound sync with a payload derived from the request. This is the only place a
     * reply can depend on the request; it runs on the server thread and MUST return promptly.
     */
    public StubPeer onSync(final Function<ClprSyncPayload, ClprSyncPayload> handler) {
        this.syncResponder = handler;
        return this;
    }

    // ── Observation (test thread) ─────────────────────────────────────────────

    /** Number of inbound sync RPCs received so far. */
    public int syncCount() {
        return syncCount.get();
    }

    /** Snapshot of all inbound sync payloads recorded so far (oldest first). */
    public List<ClprSyncPayload> received() {
        return List.copyOf(received);
    }

    /** Discard all recorded captures (use before a triggering action to assert on a clean slate). */
    public void drain() {
        received.clear();
    }

    /**
     * Block until the next inbound sync arrives, or the timeout elapses.
     *
     * @return the next recorded payload, or {@code null} on timeout
     */
    public ClprSyncPayload awaitSync(final Duration timeout) {
        try {
            return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Block until an inbound sync matching {@code predicate} arrives, discarding non-matching captures,
     * or the timeout elapses. Preferred over {@link #awaitSync(Duration)} when the loop may hold stale
     * pre-action captures.
     *
     * @return the first matching payload, or {@code null} on timeout
     */
    public ClprSyncPayload awaitSync(final Predicate<ClprSyncPayload> predicate, final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            final long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return null;
            }
            final ClprSyncPayload payload;
            try {
                payload = received.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (payload == null) {
                return null;
            }
            if (predicate.test(payload)) {
                return payload;
            }
        }
    }

    // ── Client path ───────────────────────────────────────────────────────────

    /**
     * Push a crafted sync payload into the endpoint-under-test's plaintext gRPC server and return
     * its reciprocal reply.
     *
     * @param host    the endpoint-under-test's host
     * @param port    the endpoint-under-test's gRPC port
     * @param payload the crafted sync payload
     * @return the endpoint's reply payload
     * @throws Exception if the RPC fails
     */
    public ClprSyncPayload pokeSync(final String host, final int port, final ClprSyncPayload payload) throws Exception {
        return client.sync(host, port, payload);
    }

    /**
     * Push a crafted sync payload into the endpoint-under-test's mandatory-mTLS gRPC server and
     * return its reciprocal reply. The stub peer presents its own leaf (from the
     * {@link LeafKeyManager} it was started with) as the TLS client certificate, and validates the
     * server's leaf against {@code serverCaDer}.
     *
     * <p>Requires the stub peer to have been started with a {@link LeafKeyManager} (i.e. via
     * {@link #start(LeafKeyManager)}); throws {@link IllegalStateException} otherwise because the
     * underlying {@link org.hiero.clpr.relay.grpc.client.ClprEndpointClient} would not have a client
     * identity to present.
     *
     * @param host        the endpoint-under-test's host
     * @param port        the endpoint-under-test's gRPC port
     * @param payload     the crafted sync payload
     * @param serverCaDer the relay's CA certificate (DER) used as the trust anchor for the server's
     *                    presented leaf
     * @return the endpoint's reply payload
     * @throws Exception if the RPC or TLS handshake fails
     */
    public ClprSyncPayload pokeSync(
            final String host, final int port, final ClprSyncPayload payload, final Bytes serverCaDer)
            throws Exception {
        return client.sync(host, port, payload, serverCaDer);
    }

    @Override
    public void close() {
        client.close();
        server.stop();
    }

    private ClprSyncPayload handleSync(final ClprSyncPayload request) {
        received.offer(request);
        syncCount.incrementAndGet();
        return syncResponder.apply(request);
    }

    private static ClprSyncPayload emptyReply(final ClprSyncPayload request) {
        return ClprSyncPayload.newBuilder()
                .channelId(request.channelId())
                .bundlePayload(Bytes.EMPTY)
                .build();
    }

    /** Minimal PBJ {@link ServiceInterface} exposing {@code sync} (recorded). */
    private final class StubService implements ServiceInterface {
        private static final ServiceInterface.Method SYNC = () -> "sync";

        @Override
        public String serviceName() {
            return "ClprEndpointService";
        }

        @Override
        public String fullName() {
            return "proto.ClprEndpointService";
        }

        @Override
        public List<Method> methods() {
            return List.of(SYNC);
        }

        @Override
        public Pipeline<? super Bytes> open(
                final Method method, final RequestOptions opts, final Pipeline<? super Bytes> responses)
                throws GrpcException {
            final boolean isProtobuf = opts.isProtobuf();
            if ("sync".equals(method.name())) {
                return Pipelines.<ClprSyncPayload, ClprSyncPayload>unary()
                        .mapRequest(bytes ->
                                isProtobuf ? ClprSyncPayload.PROTOBUF.parse(bytes) : ClprSyncPayload.JSON.parse(bytes))
                        .method(StubPeer.this::handleSync)
                        .mapResponse(reply -> isProtobuf
                                ? ClprSyncPayload.PROTOBUF.toBytes(reply)
                                : Bytes.wrap(ClprSyncPayload.JSON.toJSON(reply)))
                        .respondTo(responses)
                        .build();
            }
            throw new GrpcException(GrpcStatus.UNIMPLEMENTED, "Unknown method: " + method.name());
        }
    }
}
