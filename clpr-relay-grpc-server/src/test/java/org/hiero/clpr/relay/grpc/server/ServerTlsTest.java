// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import javax.net.ssl.TrustManager;
import org.hiero.clpr.relay.core.Certs;
import org.hiero.clpr.relay.core.LeafKeyManager;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class ServerTlsTest {

    // An empty-roster trust manager (trusts nobody); these tests only need it to build an SSLContext.
    private static final TrustManager EMPTY_ROSTER_TM = new ServerSideTrustManager(new PeerEndpointTlsRegistry());

    @Test
    void serverContext_withKeyManager_buildsContext() throws Exception {
        final var keyManager = new LeafKeyManager(
                Certs.parsePrivateKey(CertFixtures.CA_A_KEY_DER), Duration.ZERO, new SimpleMetrics());

        assertThat(ServerTls.serverContext(keyManager, EMPTY_ROSTER_TM)).isNotNull();
    }
}
