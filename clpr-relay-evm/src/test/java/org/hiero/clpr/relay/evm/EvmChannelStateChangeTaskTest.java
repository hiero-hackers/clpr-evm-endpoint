// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient.newStubClient;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Counter;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.PeerEndpointCache;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.junit.jupiter.api.Test;

class EvmChannelStateChangeTaskTest {

    private static final Bytes CONN = Bytes.wrap(new byte[] {1});

    /**
     * Returns channel states from a queue (re-serving the last one once drained) and an optional
     * fixed set of queued messages. Counts down a latch on every {@code readChannelState} call.
     */
    private static class StubStateReader implements ContractStateReader {
        private final Queue<Optional<ClprChannel>> states = new ArrayDeque<>();
        private List<QueuedMessage> messages = List.of();
        private volatile ClprEndpointManifest manifest = ClprEndpointManifest.DEFAULT;
        final AtomicInteger manifestReads = new AtomicInteger();
        private final CountDownLatch firstRead = new CountDownLatch(1);
        private volatile boolean messagesRead = false;

        StubStateReader add(final Optional<ClprChannel> state) {
            states.add(state);
            return this;
        }

        StubStateReader withMessages(final List<QueuedMessage> messages) {
            this.messages = messages;
            return this;
        }

        StubStateReader withManifest(final ClprEndpointManifest manifest) {
            this.manifest = manifest;
            return this;
        }

        @Override
        public Optional<ClprChannel> readChannelState(Bytes id, String blockTag) {
            firstRead.countDown();
            final var next = states.poll();
            // Re-serve the final element so a single-state queue is a stable steady state.
            if (states.isEmpty()) {
                states.add(next);
            }
            return next;
        }

        @Override
        public List<QueuedMessage> readQueuedMessages(Bytes id, long f, long t, String blockTag) {
            messagesRead = true;
            return messages;
        }

        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(CommitmentLevel l) {
            return ClprLedgerConfiguration.DEFAULT;
        }

        @Override
        public ClprEndpointManifest readPeerEndpointManifest(Bytes id, CommitmentLevel l) {
            return readPeerEndpointManifest(id, l.toBlockTag());
        }

        @Override
        public ClprEndpointManifest readPeerEndpointManifest(Bytes id, String blockTag) {
            manifestReads.incrementAndGet();
            return manifest;
        }
    }

    /** Records the most recent {@code onStateChanged} notification. */
    private static class CapturingBundleConstructor implements BundleConstructor {
        final AtomicReference<ClprChannel> lastState = new AtomicReference<>();
        final AtomicReference<List<ContractStateReader.QueuedMessage>> lastMessages = new AtomicReference<>();
        final CountDownLatch notified = new CountDownLatch(1);

        @Override
        public Optional<Bytes> getLatestBundlePayload(Bytes id) {
            return Optional.empty();
        }

        @Override
        public void onStateChanged(
                BigInteger blockNumber, Bytes id, ClprChannel s, List<ContractStateReader.QueuedMessage> m) {
            lastState.set(s);
            lastMessages.set(m);
            notified.countDown();
        }
    }

    private static ClprChannel channel(ClprChannelStatus status, long nextId, long ackedId) {
        return channel(status, nextId, ackedId, 0L);
    }

    private static ClprChannel channel(ClprChannelStatus status, long nextId, long ackedId, long manifestVersion) {
        return ClprChannel.newBuilder()
                .channelId(CONN)
                .status(status)
                .nextMessageId(nextId)
                .ackedMessageId(ackedId)
                .receivedMessageId(0L)
                .endpointManifestVersion(manifestVersion)
                .build();
    }

    private static ClprEndpoint endpoint(int accountId) {
        return ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[] {(byte) accountId}))
                .build();
    }

    private static EvmChannelStateChangeTask task(ContractStateReader reader, BundleConstructor proofConstructor) {
        return task(reader, proofConstructor, new PeerEndpointCache(), new SimpleMetrics());
    }

    private static EvmChannelStateChangeTask task(
            ContractStateReader reader,
            BundleConstructor proofConstructor,
            PeerEndpointCache peerCache,
            SimpleMetrics metrics) {
        return new EvmChannelStateChangeTask(
                CONN,
                CommitmentLevel.LATEST,
                newStubClient("http://localhost:8545"),
                reader,
                proofConstructor,
                peerCache,
                new PeerEndpointTlsRegistry(),
                metrics,
                50L, // pollIntervalMs — small so tests poll promptly
                1_000L, // backoffBaseMs
                30_000L, // backoffCapMs
                0); // proofLagBlocks
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void run_selfStopsOnClosed() {
        final ClprChannel channel = channel(ClprChannelStatus.CLOSED, 1L, 0L);
        final var reader = new StubStateReader().add(Optional.of(channel));
        final var proofConstructor = new CapturingBundleConstructor();
        final var task = task(reader, proofConstructor);

        // CLOSED is handled before any sleep, so run() returns promptly on this thread.
        task.run();

        assertThat(task.isRunning()).isFalse();
        assertThat(proofConstructor.lastState.get()).isEqualTo(channel);
    }

    @Test
    void run_notifiesProofConstructorOnStateChange() throws Exception {
        final var msgValue = ClprMessageValue.newBuilder()
                .runningHashAfterProcessing(Bytes.wrap(new byte[32]))
                .build();
        final var queued = List.of(new ContractStateReader.QueuedMessage(BigInteger.ONE, msgValue));
        final var reader = new StubStateReader()
                .add(Optional.of(channel(ClprChannelStatus.ACTIVE, 2L, 0L)))
                .withMessages(queued);
        final var proofConstructor = new CapturingBundleConstructor();
        final var task = task(reader, proofConstructor);

        final var thread = Thread.startVirtualThread(task::run);
        try {
            assertThat(proofConstructor.notified.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(proofConstructor.lastState.get().status()).isEqualTo(ClprChannelStatus.ACTIVE);
            assertThat(proofConstructor.lastMessages.get()).hasSize(1);
        } finally {
            task.stop();
            thread.interrupt();
            thread.join(2_000L);
        }
    }

    @Test
    void run_skipsTickWhenNoChannelRecord() throws Exception {
        final var reader = new StubStateReader().add(Optional.empty());
        final var proofConstructor = new CapturingBundleConstructor();
        final var task = task(reader, proofConstructor);

        final var thread = Thread.startVirtualThread(task::run);
        try {
            // Wait until the loop has polled at least once, then confirm the empty read was skipped.
            assertThat(reader.firstRead.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(proofConstructor.notified.await(300, TimeUnit.MILLISECONDS))
                    .isFalse();
            assertThat(reader.messagesRead).isFalse();
        } finally {
            task.stop();
            thread.interrupt();
            thread.join(2_000L);
        }
    }

    @Test
    void run_refreshesCacheWhenManifestVersionAdvances() throws Exception {
        // Two states with the same queue fields but an advancing endpointManifestVersion; the second
        // observation must re-read the manifest and replace the cache.
        final ClprEndpointManifest manifest = ClprEndpointManifest.newBuilder()
                .version(1L)
                .endpoints(List.of(endpoint(7)))
                .build();
        final var reader = new StubStateReader()
                .add(Optional.of(channel(ClprChannelStatus.ACTIVE, 1L, 0L, 1L)))
                .add(Optional.of(channel(ClprChannelStatus.ACTIVE, 1L, 0L, 2L)))
                .withManifest(manifest);
        final var cache = new PeerEndpointCache();
        final var metrics = new SimpleMetrics();
        final var task = task(reader, new CapturingBundleConstructor(), cache, metrics);

        final var thread = Thread.startVirtualThread(task::run);
        try {
            // First observation refreshes (1), the manifest-version advance refreshes again (2).
            awaitUntil(2_000L, () -> reader.manifestReads.get() == 2);
            assertThat(cache.allPeers())
                    .extracting(ClprEndpoint::accountId)
                    .containsExactly(endpoint(7).accountId());
            final var refreshed = (Counter) metrics.getMetric("evm.listener", "manifest.refreshed[channel_id=01]");
            assertThat(refreshed.get()).isEqualTo(2L);
        } finally {
            task.stop();
            thread.interrupt();
            thread.join(2_000L);
        }
    }

    @Test
    void run_doesNotRefreshCacheOnQueueOnlyAdvance() throws Exception {
        // Queue advances (next 1 -> 2) but endpointManifestVersion is unchanged: the manifest must
        // not be re-read beyond the single first-observation refresh.
        final ClprEndpointManifest manifest = ClprEndpointManifest.newBuilder()
                .version(1L)
                .endpoints(List.of(endpoint(7)))
                .build();
        final var reader = new StubStateReader()
                .add(Optional.of(channel(ClprChannelStatus.ACTIVE, 1L, 0L, 1L)))
                .add(Optional.of(channel(ClprChannelStatus.ACTIVE, 2L, 0L, 1L)))
                .withManifest(manifest);
        final var proofConstructor = new CapturingBundleConstructor();
        final var metrics = new SimpleMetrics();
        final var task = task(reader, proofConstructor, new PeerEndpointCache(), metrics);

        final var thread = Thread.startVirtualThread(task::run);
        try {
            // Wait until the queue advance (next == 2) has been observed by the proof constructor.
            awaitUntil(2_000L, () -> {
                final var s = proofConstructor.lastState.get();
                return s != null && s.nextMessageId() == 2L;
            });
            assertThat(reader.manifestReads.get()).isEqualTo(1);
            final var refreshed = (Counter) metrics.getMetric("evm.listener", "manifest.refreshed[channel_id=01]");
            assertThat(refreshed.get()).isEqualTo(1L);
        } finally {
            task.stop();
            thread.interrupt();
            thread.join(2_000L);
        }
    }

    @Test
    void run_populatesEmptyCacheOnFirstObservation() throws Exception {
        // A cache that booted empty is repopulated on the first poll without any manifest-version advance.
        final ClprEndpointManifest manifest = ClprEndpointManifest.newBuilder()
                .version(1L)
                .endpoints(List.of(endpoint(7)))
                .build();
        final var reader = new StubStateReader()
                .add(Optional.of(channel(ClprChannelStatus.ACTIVE, 1L, 0L, 1L)))
                .withManifest(manifest);
        final var cache = new PeerEndpointCache();
        final var task = task(reader, new CapturingBundleConstructor(), cache, new SimpleMetrics());

        final var thread = Thread.startVirtualThread(task::run);
        try {
            awaitUntil(2_000L, cache::hasPeers);
            assertThat(cache.size()).isEqualTo(1);
        } finally {
            task.stop();
            thread.interrupt();
            thread.join(2_000L);
        }
    }

    // -------------------------------------------------------------------------
    // Resilience tests: the poll loop must survive unchecked exceptions, back off with
    // growing bounded delays (no tight spin / WARN flood), and exit promptly when
    // interrupted mid-backoff.
    // -------------------------------------------------------------------------

    /** A state reader driven by a scripted list of per-call actions (last action repeats). */
    private static final class ScriptedStateReader implements ContractStateReader {
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

        @Override
        public Optional<ClprChannel> readChannelState(Bytes id, String blockTag) {
            final int i = idx.getAndIncrement();
            final Action a = i < script.size() ? script.get(i) : script.get(script.size() - 1);
            if (a == Action.THROW) {
                // Same throw site every call → stable fingerprint, mirroring a real persistent fault.
                throw new IllegalStateException("simulated RPC failure");
            }
            return Optional.of(okChannel);
        }

        @Override
        public List<QueuedMessage> readQueuedMessages(Bytes id, long f, long t, String blockTag) {
            return List.of();
        }

        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(CommitmentLevel l) {
            return ClprLedgerConfiguration.DEFAULT;
        }

        @Override
        public ClprEndpointManifest readPeerEndpointManifest(Bytes id, CommitmentLevel l) {
            return ClprEndpointManifest.DEFAULT;
        }

        @Override
        public ClprEndpointManifest readPeerEndpointManifest(Bytes id, String blockTag) {
            return ClprEndpointManifest.DEFAULT;
        }
    }

    private static EvmChannelStateChangeTask resilientTask(
            final ContractStateReader reader,
            final SimpleMetrics metrics,
            final long backoffBaseMs,
            final long backoffCapMs) {
        return new EvmChannelStateChangeTask(
                CONN,
                CommitmentLevel.LATEST,
                newStubClient("http://localhost:8545"),
                reader,
                new CapturingBundleConstructor(),
                new PeerEndpointCache(),
                new PeerEndpointTlsRegistry(),
                metrics,
                50L, // pollIntervalMs
                backoffBaseMs,
                backoffCapMs,
                0); // proofLagBlocks
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

    @Test
    void run_survivesAndRecoversWhenPollThrows() throws Exception {
        // Three faults then a clean read forever: the poll loop must survive and resume polling.
        // Backoff math (the growth/cap) is covered by FailStateTest; here we only assert resilience.
        final var reader = new ScriptedStateReader(
                List.of(
                        ScriptedStateReader.Action.THROW,
                        ScriptedStateReader.Action.THROW,
                        ScriptedStateReader.Action.THROW,
                        ScriptedStateReader.Action.OK),
                channel(ClprChannelStatus.ACTIVE, 1L, 0L));
        final var metrics = new SimpleMetrics();
        final var task = resilientTask(reader, metrics, 5L, 50L); // tiny real backoffs

        final var thread = Thread.startVirtualThread(task::run);
        try {
            // Counter is registered lazily on first increment — poll until registered and at 3.
            awaitUntil(5_000L, () -> {
                final var c = (Counter) metrics.getMetric("evm.listener", "poll.failed[channel_id=01]");
                return c != null && c.get() == 3L;
            });
            assertThat(task.isRunning()).isTrue();
        } finally {
            task.stop();
            thread.interrupt();
            thread.join(2_000L);
        }
    }

    @Test
    void run_exitsPromptlyOnInterruptDuringBackoff() throws Exception {
        // Always throws, with a long backoff so the loop is parked in Thread.sleep when interrupted.
        final var reader = new ScriptedStateReader(
                List.of(ScriptedStateReader.Action.THROW), channel(ClprChannelStatus.ACTIVE, 1L, 0L));
        final var metrics = new SimpleMetrics();
        final var task = resilientTask(reader, metrics, 60_000L, 60_000L);

        final var thread = Thread.startVirtualThread(task::run);
        try {
            // Counter is registered lazily on first increment — poll until registered and incremented.
            awaitUntil(5_000L, () -> {
                final var c = (Counter) metrics.getMetric("evm.listener", "poll.failed[channel_id=01]");
                return c != null && c.get() >= 1L; // first failure → now parked in backoff
            });
            task.stop(); // interrupts the backoff sleep
            thread.join(2_000L);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            thread.interrupt();
            thread.join(2_000L);
        }
    }
}
