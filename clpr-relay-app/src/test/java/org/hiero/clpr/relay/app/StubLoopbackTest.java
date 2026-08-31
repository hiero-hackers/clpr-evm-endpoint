// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfigurationResponse;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.QbftLedgerConfigurationPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.ParsedBundle;
import org.hiero.clpr.relay.core.PeerEndpointCache;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.PeerManifestVersionCache;
import org.hiero.clpr.relay.core.StubBundleConstructor;
import org.hiero.clpr.relay.core.TransactionSubmitter;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.core.testfixtures.PassThroughCodec;
import org.hiero.clpr.relay.grpc.client.ClprEndpointClient;
import org.hiero.clpr.relay.grpc.server.ClprGrpcServer;
import org.hiero.clpr.relay.grpc.server.ClprSyncHandler;
import org.hiero.clpr.relay.grpc.server.GetLedgerConfigurationHandler;
import org.hiero.clpr.relay.grpc.server.ThrottleEnforcer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/** Verifies the sync RPC end-to-end in a loopback configuration (client talks to a local server). */
class StubLoopbackTest {

    /** Minimal stub state reader that always returns ACTIVE for any channel. */
    static class ActiveStateReader implements ContractStateReader {
        @Override
        public Optional<ClprChannel> readChannelState(Bytes channelId, String blockTag) {
            return Optional.of(ClprChannel.newBuilder()
                    .channelId(channelId)
                    .status(ClprChannelStatus.ACTIVE)
                    .nextMessageId(1L)
                    .ackedMessageId(0L)
                    .build());
        }

        @Override
        public List<QueuedMessage> readQueuedMessages(Bytes channelId, long fromId, long toId, String blockTag) {
            return List.of();
        }

        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(CommitmentLevel level) {
            return ClprLedgerConfiguration.DEFAULT;
        }
    }

    /** Records each submitBundle call so tests can assert on what was submitted. */
    static class TrackingSubmitter implements TransactionSubmitter {
        final List<Bytes> submittedChannelIds = new ArrayList<>();

        @Override
        public void submitBundle(@NonNull ClprChannel channel, @NonNull ParsedBundle bundle) {
            submittedChannelIds.add(channel.channelId());
        }
    }

    @Test
    void syncRoundTripWithStubProofs() throws Exception {
        final var channelId = Bytes.wrap(new byte[32]); // all zeros
        final var proofConstructor = new StubBundleConstructor();

        // Seed the proof constructor with channel state so it has a cached proof
        proofConstructor.onStateChanged(BigInteger.ONE, channelId, ClprChannel.DEFAULT, List.of());

        final var txSubmitter = new TrackingSubmitter();

        final var peerCache = new PeerEndpointCache();
        peerCache.replaceAll(List.of(ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[] {0x01}))
                .build()));

        final var syncHandler = new ClprSyncHandler(
                _ -> new PassThroughCodec(),
                proofConstructor,
                txSubmitter,
                id -> new ThrottleEnforcer(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                new ActiveStateReader(),
                new PeerManifestVersionCache(),
                new SimpleMetrics(),
                "");

        final var ledgerConfigHandler = new GetLedgerConfigurationHandler(
                _ -> _ -> ClprLedgerConfigurationResponse.newBuilder()
                        .qbft(QbftLedgerConfigurationPayload.DEFAULT)
                        .build(),
                CommitmentLevel.FINALIZED);
        final int port;
        final int infoPort;
        try (var s1 = new java.net.ServerSocket(0);
                var s2 = new java.net.ServerSocket(0)) {
            port = s1.getLocalPort();
            infoPort = s2.getLocalPort();
        }
        final var server = new ClprGrpcServer(
                new ClprGrpcServer.Listeners(port, infoPort, null),
                1_048_576,
                syncHandler,
                new PeerEndpointTlsRegistry(),
                ledgerConfigHandler,
                "");
        server.start();

        try {
            assertThat(server.port()).isEqualTo(port);
            assertThat(server.isRunning()).isTrue();

            // Build outbound payload with stub proof; sign per CLPR design formula
            final Bytes outboundProof =
                    proofConstructor.getLatestBundlePayload(channelId).orElseThrow();
            assertThat(outboundProof.length()).isGreaterThan(0);

            final var outbound = ClprSyncPayload.newBuilder()
                    .channelId(channelId)
                    .bundlePayload(outboundProof)
                    .build();

            final var client = new ClprEndpointClient(1_048_576, null);
            final var response = client.sync("localhost", port, outbound);

            // Verify response fields
            assertThat(response).isNotNull();
            assertThat(response.channelId()).isEqualTo(channelId);
            assertThat(response.bundlePayload().length()).isGreaterThan(0);

            // Verify the inbound bundle actually reached the submitter with the correct channelId
            assertThat(txSubmitter.submittedChannelIds).hasSize(1);
            assertThat(txSubmitter.submittedChannelIds.get(0)).isEqualTo(channelId);
        } finally {
            server.stop();
        }
    }
}
