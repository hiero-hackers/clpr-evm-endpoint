// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import org.hiero.clpr.relay.core.Certs;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClprGrpcServerTest {

    private static final int MAX_MSG = 1 << 20;

    private ClprGrpcServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** Returns {@code n} distinct, momentarily-free local ports. */
    private static int[] freePorts(final int n) throws IOException {
        final var sockets = new ArrayList<ServerSocket>();
        try {
            final int[] ports = new int[n];
            for (int i = 0; i < n; i++) {
                final var s = new ServerSocket(0);
                sockets.add(s);
                ports[i] = s.getLocalPort();
            }
            return ports;
        } finally {
            for (final var s : sockets) {
                s.close();
            }
        }
    }

    private static GetLedgerConfigurationHandler handler() {
        return new GetLedgerConfigurationHandler(
                _ -> {
                    throw new UnsupportedOperationException("not exercised");
                },
                CommitmentLevel.FINALIZED);
    }

    @Test
    void plaintextSync_startsSyncAndInfo() throws Exception {
        final int[] p = freePorts(2);
        server = new ClprGrpcServer(
                new ClprGrpcServer.Listeners(p[0], p[1], null),
                MAX_MSG,
                null,
                new PeerEndpointTlsRegistry(),
                handler(),
                "test");

        server.start();

        assertThat(server.isRunning()).isTrue();
        assertThat(server.port()).isEqualTo(p[0]); // sync holds the default socket
        assertThat(server.infoPort()).isEqualTo(p[1]); // plaintext info
    }

    @Test
    void secureSync_startsMtlsSyncAndPlaintextInfo() throws Exception {
        final var keyManager = new LeafKeyManager(
                Certs.parsePrivateKey(CertFixtures.CA_A_KEY_DER), Duration.ZERO, new SimpleMetrics());
        final int[] p = freePorts(2);
        server = new ClprGrpcServer(
                new ClprGrpcServer.Listeners(p[0], p[1], keyManager),
                MAX_MSG,
                null,
                new PeerEndpointTlsRegistry(),
                handler(),
                "test");

        server.start();

        assertThat(server.isRunning()).isTrue();
        assertThat(server.port()).isEqualTo(p[0]); // mTLS sync holds the default socket
        assertThat(server.infoPort()).isEqualTo(p[1]); // info is plaintext
    }
}
