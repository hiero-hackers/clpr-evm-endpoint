// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.BundlePayloadCodec;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.ParsedBundle;
import org.hiero.clpr.relay.core.PeerEndpointCache;
import org.hiero.clpr.relay.core.PeerManifestVersionCache;
import org.hiero.clpr.relay.core.TransactionSubmitter;
import org.hiero.clpr.relay.core.metrics.MetricLabels;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.grpc.client.ClprEndpointClient;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChannelSyncTaskTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {1, 2, 3});
    public static final long TEST_SYNC_INTERVAL_MS = 1000L;

    // -------------------------------------------------------------------------
    // Minimal stub implementations (no Mockito needed — avoids JPMS friction)
    // -------------------------------------------------------------------------

    /** A ContractStateReader whose returned channel can be configured per test. */
    static class StubStateReader implements ContractStateReader {
        ClprChannel channel;

        StubStateReader(ClprChannel channel) {
            this.channel = channel;
        }

        @NonNull
        @Override
        public Optional<ClprChannel> readChannelState(@NonNull Bytes channelId, @NonNull String blockTag) {
            return Optional.ofNullable(channel);
        }

        @NonNull
        @Override
        public List<QueuedMessage> readQueuedMessages(
                @NonNull Bytes channelId, long fromId, long toId, @NonNull String blockTag) {
            return List.of();
        }

        @NonNull
        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(@NonNull CommitmentLevel level) {
            return ClprLedgerConfiguration.DEFAULT;
        }
    }

    static class EmptyBundleConstructor implements BundleConstructor {
        @NonNull
        @Override
        public Optional<Bytes> getLatestBundlePayload(@NonNull Bytes id) {
            return Optional.empty();
        }

        @Override
        public void onStateChanged(
                @NonNull BigInteger blockNumber,
                @NonNull Bytes id,
                @NonNull ClprChannel s,
                @NonNull List<ContractStateReader.QueuedMessage> m) {}
    }

    static class PresentBundleConstructor implements BundleConstructor {
        @NonNull
        @Override
        public Optional<Bytes> getLatestBundlePayload(@NonNull Bytes id) {
            return Optional.of(Bytes.wrap(new byte[] {0x01}));
        }

        @Override
        public void onStateChanged(
                @NonNull BigInteger blockNumber,
                @NonNull Bytes id,
                @NonNull ClprChannel s,
                @NonNull List<ContractStateReader.QueuedMessage> m) {}
    }

    /** A BundleConstructor that records whether {@link #getLatestBundlePayload} was consulted. */
    static class RecordingBundleConstructor implements BundleConstructor {
        volatile boolean bundleProofRequested = false;

        @NonNull
        @Override
        public Optional<Bytes> getLatestBundlePayload(@NonNull Bytes id) {
            bundleProofRequested = true;
            return Optional.of(Bytes.wrap(new byte[] {0x01}));
        }

        @Override
        public void onStateChanged(
                @NonNull BigInteger blockNumber,
                @NonNull Bytes id,
                @NonNull ClprChannel s,
                @NonNull List<ContractStateReader.QueuedMessage> m) {}
    }

    static class NoOpCodec implements BundlePayloadCodec {
        @NonNull
        @Override
        public ParsedBundle decodeBundle(@NonNull Bytes p) {
            throw new UnsupportedOperationException();
        }

        @NonNull
        @Override
        public ParsedBundle parseBundle(@NonNull Bytes p, long receivedMessageId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Returns a ParsedBundle whose nextMessageId is configurable. Messages list is empty. */
    static class StubCodec implements BundlePayloadCodec {
        private final long nextMessageId;
        private final long endpointManifestVersion;

        StubCodec(long nextMessageId) {
            this(nextMessageId, 0L);
        }

        StubCodec(long nextMessageId, long endpointManifestVersion) {
            this.nextMessageId = nextMessageId;
            this.endpointManifestVersion = endpointManifestVersion;
        }

        @NonNull
        @Override
        public ParsedBundle decodeBundle(@NonNull Bytes p) {
            var metadata = ClprQueueMetadata.newBuilder()
                    .nextMessageId(nextMessageId)
                    .endpointManifestVersion(endpointManifestVersion)
                    .build();
            return new ParsedBundle(metadata, List.of(), p);
        }

        @NonNull
        @Override
        public ParsedBundle parseBundle(@NonNull Bytes p, long receivedMessageId) {
            return decodeBundle(p);
        }
    }

    /**
     * Returns a ParsedBundle with a configurable nextMessageId AND an explicit message list.
     *
     * <p>Used to test stale-skip behaviour when the bundle carries concrete messages so that
     * {@code bundleFirstId = nextMessageId - messages.size()} is meaningful.
     */
    static class StubCodecWithMessages implements BundlePayloadCodec {
        private final long nextMessageId;
        private final int messageCount;
        private final ClprChannelStatus status;

        StubCodecWithMessages(long nextMessageId, int messageCount, ClprChannelStatus status) {
            this.nextMessageId = nextMessageId;
            this.messageCount = messageCount;
            this.status = status;
        }

        @NonNull
        @Override
        public ParsedBundle decodeBundle(@NonNull Bytes p) {
            var metadata = ClprQueueMetadata.newBuilder()
                    .nextMessageId(nextMessageId)
                    .status(status)
                    .build();
            // Build a list of dummy payloads so messages().size() == messageCount.
            final var messages = new ArrayList<ClprMessagePayload>();
            for (int i = 0; i < messageCount; i++) {
                messages.add(ClprMessagePayload.DEFAULT);
            }
            return new ParsedBundle(metadata, messages, p);
        }

        @NonNull
        @Override
        public ParsedBundle parseBundle(@NonNull Bytes p, long receivedMessageId) {
            return decodeBundle(p);
        }
    }

    static class NoOpSubmitter implements TransactionSubmitter {
        boolean called = false;

        @Override
        public void submitBundle(@NonNull ClprChannel channel, @NonNull ParsedBundle bundle) {
            called = true;
        }
    }

    static class EmptyPeerSelector extends PeerSelector {
        EmptyPeerSelector() {
            super(new PeerEndpointCache());
        }
    }

    /** A PeerSelector that returns a peer with a real service endpoint (needed for sync call). */
    static class SinglePeerSelectorWithEndpoint extends PeerSelector {
        SinglePeerSelectorWithEndpoint() {
            super(makeCache());
        }

        private static PeerEndpointCache makeCache() {
            var cache = new PeerEndpointCache();
            var endpoint = new ClprServiceEndpoint("127.0.0.1", 19999);
            cache.replaceAll(List.of(new ClprEndpoint(endpoint, Bytes.EMPTY, Bytes.wrap(new byte[] {9}))));
            return cache;
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    private static ClprEndpointClient stubPayloadClprEndpointClient(
            Function<ClprSyncPayload, ClprSyncPayload> provider) {
        return new ClprEndpointClient(1_048_576, null) {
            @Override
            public ClprSyncPayload sync(
                    final String host,
                    final int port,
                    final ClprSyncPayload outboundPayload,
                    final Bytes peerTlsCertificate) {
                return provider.apply(outboundPayload);
            }
        };
    }

    private static final ClprEndpointClient NO_OP_CLPR_ENDPOINT_CLIENT =
            stubPayloadClprEndpointClient(_ -> ClprSyncPayload.newBuilder().build());

    private static final ClprEndpointClient NON_EMPTY_CLPR_ENDPOINT_CLIENT =
            stubPayloadClprEndpointClient(_ -> ClprSyncPayload.newBuilder()
                    .bundlePayload(Bytes.wrap(new byte[] {0x42}))
                    .build());

    private static final long TEST_BACKOFF_BASE_MS = 1_000L;
    private static final long TEST_BACKOFF_CAP_MS = 30_000L;

    /** Fresh per test method (JUnit PER_METHOD lifecycle); the outbound leg records peer versions here. */
    private final PeerManifestVersionCache peerManifestVersions = new PeerManifestVersionCache();

    /** Build a task with unlimited throttle, the given metrics, and the default test backoff policy. */
    private ChannelSyncTask task(
            ContractStateReader reader,
            BundleConstructor proof,
            BundlePayloadCodec codec,
            TransactionSubmitter submitter,
            PeerSelector peer,
            ClprEndpointClient clprEndpointClient,
            Metrics metrics) {
        return new ChannelSyncTask(
                CHANNEL_ID,
                CommitmentLevel.LATEST,
                reader,
                proof,
                codec,
                submitter,
                peer,
                clprEndpointClient,
                peerManifestVersions,
                metrics,
                TEST_BACKOFF_BASE_MS,
                TEST_BACKOFF_CAP_MS,
                TEST_SYNC_INTERVAL_MS);
    }

    /** Same as {@link #task} with a throwaway metrics registry. */
    private ChannelSyncTask task(
            ContractStateReader reader,
            BundleConstructor proof,
            BundlePayloadCodec codec,
            TransactionSubmitter submitter,
            PeerSelector peer,
            ClprEndpointClient clprEndpointClient) {
        return task(reader, proof, codec, submitter, peer, clprEndpointClient, new SimpleMetrics());
    }

    private ChannelSyncTask makeTask(ContractStateReader reader, BundleConstructor proof, PeerSelector peer) {
        return task(reader, proof, new NoOpCodec(), new NoOpSubmitter(), peer, NO_OP_CLPR_ENDPOINT_CLIENT);
    }

    @Test
    void stopsOnClosedChannel() {
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.CLOSED)
                .nextMessageId(1L)
                .ackedMessageId(0L)
                .build();
        var task = makeTask(
                new ChannelSyncTaskTest.StubStateReader(channel),
                new ChannelSyncTaskTest.EmptyBundleConstructor(),
                new ChannelSyncTaskTest.EmptyPeerSelector());

        task.executeSyncCycle();

        assertThat(task.isRunning()).isFalse();
    }

    @Test
    void getsBundlesEvenWhenOutboundQueueEmpty() {
        // nextMessageId == ackedMessageId → no outbound messages, but the cycle must still consult
        // the proof constructor to collect inbound bundles. Assert the constructor IS consulted.
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(5L)
                .build();
        var proof = new ChannelSyncTaskTest.RecordingBundleConstructor();
        var peer = new EmptyPeerSelector(); // exit later at no-peer step; not the point of this test
        var task = makeTask(new StubStateReader(channel), proof, peer);

        task.executeSyncCycle();

        assertThat(proof.bundleProofRequested).isTrue();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void submitsWhenBundleHasNewMessages() {
        // receivedMessageId=3, bundleNext=5 → bundle has messages 3 and 4, submit
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(3L)
                .receivedMessageId(3L)
                .build();
        var submitter = new NoOpSubmitter();
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new StubCodec(5L), // bundleNext=5 > receivedMessageId=3
                submitter,
                new SinglePeerSelectorWithEndpoint(),
                NON_EMPTY_CLPR_ENDPOINT_CLIENT);

        task.executeSyncCycle();

        assertThat(submitter.called).isTrue();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void recordsPeerManifestVersionOnOutboundLeg() {
        // The peer's response reports it holds our manifest at version 7. On the outbound leg this is
        // the only place that version surfaces, so the task must record it into the shared cache —
        // otherwise an outbound-only topology (the peer never inbound-syncs to us) would keep the
        // cached known-version at 0 and re-attach our manifest proof to every bundle forever.
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(3L)
                .receivedMessageId(3L)
                .build();
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new StubCodec(5L, 7L), // peer reports holding our manifest at version 7
                new NoOpSubmitter(),
                new SinglePeerSelectorWithEndpoint(),
                NON_EMPTY_CLPR_ENDPOINT_CLIENT);

        assertThat(peerManifestVersions.knownVersion(CHANNEL_ID)).isEqualTo(0L);
        task.executeSyncCycle();
        assertThat(peerManifestVersions.knownVersion(CHANNEL_ID)).isEqualTo(7L);
    }

    /**
     * A bundle whose first message ID equals {@code besuReceived+1} must be submitted.
     *
     * <p>Chain-B has processed messages 1 and 2 ({@code receivedMessageId=2}). The peer
     * returns a bundle containing only message 3 ({@code bundleNext=4}, {@code messageCount=1}
     * → {@code bundleFirstId=3}). Because {@code bundleFirstId=3} equals
     * {@code besuReceived+1=3}, this is the next expected message and the bundle is submitted.
     */
    @Test
    void submitsWhenBundleStartsAtExpectedMessageId() {
        // Chain-B: processed through message 2, expects message 3 next
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(1L)
                .ackedMessageId(0L)
                .receivedMessageId(2L)
                .build();
        var submitter = new NoOpSubmitter();
        // bundleNext=4, messageCount=1 → bundleFirstId=3; equals besuReceived+1=3
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new StubCodecWithMessages(4L, 1, ClprChannelStatus.ACTIVE),
                submitter,
                new SinglePeerSelectorWithEndpoint(),
                NON_EMPTY_CLPR_ENDPOINT_CLIENT);

        task.executeSyncCycle();

        assertThat(submitter.called).isTrue();
    }

    @Test
    void skipsWhenNoProofCached() {
        // Pending messages but no proof
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(2L)
                .build();
        var submitter = new NoOpSubmitter();
        var task = task(
                new StubStateReader(channel),
                new EmptyBundleConstructor(),
                new NoOpCodec(),
                submitter,
                new EmptyPeerSelector(),
                NO_OP_CLPR_ENDPOINT_CLIENT);

        task.executeSyncCycle();

        assertThat(submitter.called).isFalse();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void doesNotSyncWhenNoProofCached() {
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(5L)
                .build();
        var recording = new RecordingClprEndpointClient();
        var task = task(
                new StubStateReader(channel),
                new EmptyBundleConstructor(),
                new NoOpCodec(),
                new NoOpSubmitter(),
                new SinglePeerSelectorWithEndpoint(),
                recording);

        task.executeSyncCycle();

        assertThat(recording.callCount).isEqualTo(0);
    }

    @Test
    void skipsWhenNoPeersAvailable() throws InterruptedException {
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(2L)
                .build();
        var submitter = new NoOpSubmitter();
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new NoOpCodec(),
                submitter,
                new EmptyPeerSelector(),
                NO_OP_CLPR_ENDPOINT_CLIENT); // no peers

        task.executeSyncCycle();

        assertThat(submitter.called).isFalse();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void executeSyncCycle_syncsWhenPaused() throws InterruptedException {
        // Spec §3.1.3 / §4.5: PAUSED keeps syncing — auto-resume needs the peer's next
        // correctly-ordered bundle, which requires both sides to stay reachable.
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.PAUSED)
                .nextMessageId(5L)
                .ackedMessageId(2L)
                .build();
        var submitter = new NoOpSubmitter();
        final boolean[] syncCalled = {false};
        final var trackingClient = new ClprEndpointClient(1_048_576, null) {
            @Override
            public ClprSyncPayload sync(
                    final String host,
                    final int port,
                    final ClprSyncPayload outboundPayload,
                    final Bytes peerTlsCertificate) {
                syncCalled[0] = true;
                return ClprSyncPayload.newBuilder().build();
            }
        };
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new NoOpCodec(),
                submitter,
                new SinglePeerSelectorWithEndpoint(),
                trackingClient);

        task.executeSyncCycle();

        assertThat(syncCalled[0]).isTrue();
        // Empty response bundle ⇒ nothing to submit on chain.
        assertThat(submitter.called).isFalse();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void executeSyncCycle_syncsWhenClosing() throws InterruptedException {
        // Spec §3.1.3 / §4.2 step 5b: CLOSING keeps shipping bundles so the peer can drain
        // acks and the chain can auto-transition to DRAINED.
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.CLOSING)
                .nextMessageId(5L)
                .ackedMessageId(2L)
                .build();
        var submitter = new NoOpSubmitter();
        final boolean[] syncCalled = {false};
        final var trackingClient = new ClprEndpointClient(1_048_576, null) {
            @Override
            public ClprSyncPayload sync(
                    final String host,
                    final int port,
                    final ClprSyncPayload outboundPayload,
                    final Bytes peerTlsCertificate) {
                syncCalled[0] = true;
                return ClprSyncPayload.newBuilder().build();
            }
        };
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new NoOpCodec(),
                submitter,
                new SinglePeerSelectorWithEndpoint(),
                trackingClient);

        task.executeSyncCycle();

        assertThat(syncCalled[0]).isTrue();
        assertThat(submitter.called).isFalse();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void executeSyncCycle_syncsWhenDrained() throws InterruptedException {
        // Spec §3.1.3 / §4.2 step 5a: DRAINED still ships bundles so the peer's DRAINED
        // metadata can drive the auto-transition to CLOSED.
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.DRAINED)
                .nextMessageId(5L)
                .ackedMessageId(2L)
                .build();
        var submitter = new NoOpSubmitter();
        final boolean[] syncCalled = {false};
        final var trackingClient = new ClprEndpointClient(1_048_576, null) {
            @Override
            public ClprSyncPayload sync(
                    final String host,
                    final int port,
                    final ClprSyncPayload outboundPayload,
                    final Bytes peerTlsCertificate) {
                syncCalled[0] = true;
                return ClprSyncPayload.newBuilder().build();
            }
        };
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new NoOpCodec(),
                submitter,
                new SinglePeerSelectorWithEndpoint(),
                trackingClient);

        task.executeSyncCycle();

        assertThat(syncCalled[0]).isTrue();
        assertThat(submitter.called).isFalse();
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void executeSyncCycle_idlesWhenNoChannelRecord() throws InterruptedException {
        // Empty Optional ⇒ no Channel record on chain (PENDING or unregistered). Spec
        // §3.1.3: "no messaging, no syncing." The task must idle without an outbound RPC.
        var submitter = new NoOpSubmitter();
        final boolean[] syncCalled = {false};
        final var trackingClient = new ClprEndpointClient(1_048_576, null) {
            @Override
            public ClprSyncPayload sync(
                    final String host,
                    final int port,
                    final ClprSyncPayload outboundPayload,
                    final Bytes peerTlsCertificate) {
                syncCalled[0] = true;
                return ClprSyncPayload.newBuilder().build();
            }
        };
        var task = task(
                new StubStateReader(null), // null => Optional.empty()
                new PresentBundleConstructor(),
                new NoOpCodec(),
                submitter,
                new SinglePeerSelectorWithEndpoint(),
                trackingClient);

        task.executeSyncCycle();

        assertThat(syncCalled[0]).isFalse();
        assertThat(submitter.called).isFalse();
        assertThat(task.isRunning()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Throttle tests
    // -------------------------------------------------------------------------

    @Test
    void channelIdIsPreserved() {
        var task = makeTask(
                new StubStateReader(ClprChannel.DEFAULT), new EmptyBundleConstructor(), new EmptyPeerSelector());
        assertThat(task.channelId()).isEqualTo(CHANNEL_ID);
    }

    @Test
    void isRunningByDefault() {
        var task = makeTask(
                new StubStateReader(ClprChannel.DEFAULT), new EmptyBundleConstructor(), new EmptyPeerSelector());
        assertThat(task.isRunning()).isTrue();
    }

    @Test
    void stopSetsRunningFalse() {
        var task = makeTask(
                new StubStateReader(ClprChannel.DEFAULT), new EmptyBundleConstructor(), new EmptyPeerSelector());
        task.stop();
        assertThat(task.isRunning()).isFalse();
    }

    @Test
    void swallowsSyncFailureAfterStop() {
        // Once stopped, a peer RPC that fails because its channel was closed mid-shutdown is
        // expected teardown: the cycle must neither propagate nor count it as a failure.
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(3L)
                .receivedMessageId(3L)
                .build();
        final var closedClient = new ClprEndpointClient(1_048_576, null) {
            @Override
            public ClprSyncPayload sync(
                    final String host,
                    final int port,
                    final ClprSyncPayload outboundPayload,
                    final Bytes peerTlsCertificate) {
                throw new IllegalStateException("ClprEndpointClient is closed");
            }
        };
        var metrics = new SimpleMetrics();
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new StubCodec(5L),
                new NoOpSubmitter(),
                new SinglePeerSelectorWithEndpoint(),
                closedClient,
                metrics);

        task.stop();

        task.executeSyncCycle();

        // Shutdown-induced failures are not recorded — the counter is never registered.
        final String channelIdLabel = HexFormat.of().formatHex(CHANNEL_ID.toByteArray());
        var cyclesFailed = (Counter) metrics.getMetric(
                "sync",
                MetricLabels.labeled("cycles.failed", Map.of("channel_id", channelIdLabel, "reason", "runtime_error")));
        assertThat(cyclesFailed).isNull();
    }

    /** ClprEndpointClient that records the last call's arguments. */
    static class RecordingClprEndpointClient extends ClprEndpointClient {
        RecordingClprEndpointClient() {
            super(1_048_576, null);
        }

        String host;
        int port;
        Bytes channelId;
        Bytes bundle;
        int callCount;

        @NonNull
        @Override
        public ClprSyncPayload sync(
                @NonNull String h, int p, @NonNull ClprSyncPayload syncPayload, @NonNull Bytes peerTlsCertificate) {
            host = h;
            port = p;
            channelId = syncPayload.channelId();
            bundle = syncPayload.bundlePayload();
            callCount++;
            return ClprSyncPayload.newBuilder().build();
        }
    }

    @Test
    void dispatchesSyncWhenPeerHasServiceEndpoint() throws InterruptedException {
        var channel = ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(2L)
                .build();
        var endpoint = ClprEndpoint.newBuilder()
                .serviceEndpoint(new ClprServiceEndpoint("10.0.0.1", 9000))
                .accountId(Bytes.wrap(new byte[] {7}))
                .build();
        var cache = new PeerEndpointCache();
        cache.replaceAll(List.of(endpoint));
        var peerSelector = new PeerSelector(cache);

        var recording = new RecordingClprEndpointClient();
        var task = task(
                new StubStateReader(channel),
                new PresentBundleConstructor(),
                new NoOpCodec(),
                new NoOpSubmitter(),
                peerSelector,
                recording);

        task.executeSyncCycle();

        assertThat(recording.callCount).isEqualTo(1);
        assertThat(recording.host).isEqualTo("10.0.0.1");
        assertThat(recording.port).isEqualTo(9000);
        assertThat(recording.channelId).isEqualTo(CHANNEL_ID);
        assertThat(recording.bundle).isEqualTo(Bytes.wrap(new byte[] {0x01})); // proof
    }

    // -------------------------------------------------------------------------
    // Resilience tests: the run() loop must survive unchecked exceptions, back off
    // with growing bounded delays, reset on the first clean cycle, and exit promptly
    // when interrupted mid-backoff.
    // -------------------------------------------------------------------------

    /** A state reader driven by a scripted list of per-call actions (last action repeats). */
    static final class ScriptedStateReader implements ContractStateReader {
        enum Action {
            THROW,
            OK
        }

        private final List<Action> script;
        private final ClprChannel okChannel;
        private final AtomicInteger idx = new AtomicInteger(0);

        ScriptedStateReader(final List<Action> script, final ClprChannel okChannel) {
            this.script = script;
            this.okChannel = okChannel;
        }

        @NonNull
        @Override
        public Optional<ClprChannel> readChannelState(@NonNull Bytes channelId, @NonNull String blockTag) {
            final int i = idx.getAndIncrement();
            final Action a = i < script.size() ? script.get(i) : script.get(script.size() - 1);
            if (a == Action.THROW) {
                // Same throw site every call → stable fingerprint, mirroring a real persistent fault.
                throw new IllegalStateException("simulated RPC failure");
            }
            return Optional.of(okChannel);
        }

        @NonNull
        @Override
        public List<QueuedMessage> readQueuedMessages(
                @NonNull Bytes channelId, long fromId, long toId, @NonNull String blockTag) {
            return List.of();
        }

        @NonNull
        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(@NonNull CommitmentLevel level) {
            return ClprLedgerConfiguration.DEFAULT;
        }
    }

    private static ChannelSyncTask resilientTask(
            final ContractStateReader reader,
            final SimpleMetrics metrics,
            final long backoffBaseMs,
            final long backoffCapMs) {
        return new ChannelSyncTask(
                CHANNEL_ID,
                CommitmentLevel.LATEST,
                reader,
                new EmptyBundleConstructor(),
                new NoOpCodec(),
                new NoOpSubmitter(),
                new EmptyPeerSelector(),
                NO_OP_CLPR_ENDPOINT_CLIENT,
                new PeerManifestVersionCache(),
                metrics,
                backoffBaseMs,
                backoffCapMs,
                TEST_SYNC_INTERVAL_MS);
    }

    private static ClprChannel activeNoOutbound() {
        return ClprChannel.newBuilder()
                .status(ClprChannelStatus.ACTIVE)
                .nextMessageId(5L)
                .ackedMessageId(5L)
                .build();
    }

    private static void awaitUntil(final long timeoutMs, final BooleanSupplier cond) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("condition not met within " + timeoutMs + " ms");
    }

    // Each per-cycle collaborator that runs BEFORE the peer RPC is trapped by the channel-level
    // FailState (not the peer-scoped inner catch). When any of them throws, the loop must stay alive,
    // advance the task's FailState streak, and clear on the first clean cycle. The task is launched on
    // a real virtual thread so run()'s failure handling is exercised end-to-end. (The aggregate
    // sync.channels.failing gauge that reads this streak is owned by RelayInstance, tested there.)

    /** While {@code active}, the targeted collaborator throws; cleared to let the loop recover. */
    private static final class Fault {
        volatile boolean active = true;
    }

    /** The per-cycle collaborator whose unchecked exception {@code FailState} must trap. */
    private enum FailingStep {
        READ_STATE,
        GET_PROOF,
        SELECT_PEER
    }

    @ParameterizedTest
    @EnumSource(FailingStep.class)
    void run_trapsAndRecoversFromEachPreRpcFailure(final FailingStep step) throws Exception {
        final var metrics = new SimpleMetrics();
        final var fault = new Fault();
        final var task = taskFailingAt(step, fault, metrics);
        final var thread = Thread.startVirtualThread(task::run); // real run() on a virtual thread
        try {
            // The step's exception propagates to run()'s FailState catch → the streak rises.
            awaitUntil(2_000L, () -> task.failState().consecutiveFailures() >= 1);
            assertThat(cyclesFailed(metrics)).isGreaterThanOrEqualTo(1L);

            // The first clean cycle clears the streak.
            fault.active = false;
            awaitUntil(2_000L, () -> task.failState().consecutiveFailures() == 0);
        } finally {
            task.stop();
            thread.join(2_000L);
        }
    }

    /** Wire a task whose only failing collaborator is {@code step}; everything upstream of it succeeds. */
    private static ChannelSyncTask taskFailingAt(
            final FailingStep step, final Fault fault, final SimpleMetrics metrics) {
        final ContractStateReader reader =
                step == FailingStep.READ_STATE ? readerFailingWhile(fault) : new StubStateReader(activeNoOutbound());
        final BundleConstructor proof =
                switch (step) {
                    case GET_PROOF -> proofFailingWhile(fault);
                    case SELECT_PEER ->
                        new PresentBundleConstructor(); // non-empty proof so selectPeer()/sign() is reached
                    default -> new EmptyBundleConstructor();
                };
        final PeerSelector peer =
                switch (step) {
                    case SELECT_PEER -> selectorFailingWhile(fault);
                    default -> new EmptyPeerSelector();
                };
        return new ChannelSyncTask(
                CHANNEL_ID,
                CommitmentLevel.LATEST,
                reader,
                proof,
                new NoOpCodec(),
                new NoOpSubmitter(),
                peer,
                NO_OP_CLPR_ENDPOINT_CLIENT,
                new PeerManifestVersionCache(),
                metrics,
                5L, // tiny backoff so the loop churns and recovers fast
                50L,
                TEST_SYNC_INTERVAL_MS);
    }

    private static ContractStateReader readerFailingWhile(final Fault fault) {
        return new ContractStateReader() {
            @NonNull
            @Override
            public Optional<ClprChannel> readChannelState(@NonNull Bytes id, @NonNull String tag) {
                if (fault.active) {
                    throw new IllegalStateException("readChannelState failed");
                }
                return Optional.of(activeNoOutbound());
            }

            @NonNull
            @Override
            public List<QueuedMessage> readQueuedMessages(@NonNull Bytes id, long f, long t, @NonNull String tag) {
                return List.of();
            }

            @NonNull
            @Override
            public ClprLedgerConfiguration readLedgerConfiguration(@NonNull CommitmentLevel l) {
                return ClprLedgerConfiguration.DEFAULT;
            }
        };
    }

    private static BundleConstructor proofFailingWhile(final Fault fault) {
        return new BundleConstructor() {
            @NonNull
            @Override
            public Optional<Bytes> getLatestBundlePayload(@NonNull Bytes id) {
                if (fault.active) {
                    throw new IllegalStateException("getLatestBundlePayload failed");
                }
                return Optional.empty();
            }

            @Override
            public void onStateChanged(
                    @NonNull BigInteger b,
                    @NonNull Bytes id,
                    @NonNull ClprChannel s,
                    @NonNull List<ContractStateReader.QueuedMessage> m) {}
        };
    }

    private static PeerSelector selectorFailingWhile(final Fault fault) {
        return new PeerSelector(new PeerEndpointCache()) {
            @Override
            public Optional<ClprEndpoint> selectPeer() {
                if (fault.active) {
                    throw new IllegalStateException("selectPeer failed");
                }
                return Optional.empty();
            }
        };
    }

    private static long cyclesFailed(final SimpleMetrics metrics) {
        // cyclesFailed is a LabeledCounter registered lazily with channel_id + reason labels.
        final String channelIdLabel = HexFormat.of().formatHex(CHANNEL_ID.toByteArray());
        return metrics.findMetricsByCategory("sync").stream()
                .filter(m ->
                        m.getName().startsWith("cycles.failed") && m.getName().contains("channel_id=" + channelIdLabel))
                .filter(Counter.class::isInstance)
                .mapToLong(m -> ((Counter) m).get())
                .sum();
    }

    @Test
    void run_exitsPromptlyOnInterruptDuringBackoff() throws Exception {
        // Always throws, with a long backoff so the loop is parked in Thread.sleep when interrupted.
        final var reader = new ScriptedStateReader(List.of(ScriptedStateReader.Action.THROW), activeNoOutbound());
        final var metrics = new SimpleMetrics();
        final var task = resilientTask(reader, metrics, 60_000L, 60_000L);

        final var thread = Thread.startVirtualThread(task::run);
        try {
            awaitUntil(5_000L, () -> cyclesFailed(metrics) >= 1L); // first failure → now parked in backoff
            task.stop(); // interrupts the backoff sleep
            thread.join(2_000L);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            thread.interrupt();
            thread.join(2_000L);
        }
    }
}
