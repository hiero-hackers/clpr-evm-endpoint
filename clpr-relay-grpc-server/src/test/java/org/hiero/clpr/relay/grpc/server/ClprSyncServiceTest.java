// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class ClprSyncServiceTest {

    private static final Bytes CONN = Bytes.wrap(new byte[] {1, 2, 3});

    @Test
    void advertisesOnlySync() {
        final var service = new ClprSyncService(null, new PeerEndpointTlsRegistry());

        assertThat(service.methods()).extracting(ServiceInterface.Method::name).containsExactly("sync");
    }

    @Test
    void rejectsNonSyncMethods() {
        final var service = new ClprSyncService(null, new PeerEndpointTlsRegistry());

        // The guard rejects the method before touching request options/responses, so null args are safe.
        assertThatThrownBy(() -> service.open(() -> "discoverEndpoints", null, null))
                .isInstanceOf(GrpcException.class);
        assertThatThrownBy(() -> service.open(() -> "getLedgerConfiguration", null, null))
                .isInstanceOf(GrpcException.class);
    }

    @Test
    void peer_resolvesClientByItsIssuingRosterCa() {
        final ClprEndpoint registered = ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[] {7, 7}))
                .tlsCertificate(Bytes.wrap(CertFixtures.CA_A_CERT_DER))
                .build();
        final var registry = new PeerEndpointTlsRegistry();
        registry.update(CONN, List.of(registered));
        final var service = new ClprSyncService(null, registry);

        // A leaf issued by the registered CA resolves to that endpoint.
        assertThat(service.peer(CertFixtures.leafUnderA().cert())).contains(registered);
        // A leaf issued by a different CA, and no certificate at all, resolve to nothing.
        assertThat(service.peer(CertFixtures.leafUnderB().cert())).isEmpty();
        assertThat(service.peer(null)).isEmpty();
    }
}
