// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprGetLedgerConfigurationRequest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfigurationResponse;
import com.hedera.hapi.node.state.clpr.QbftLedgerConfigurationPayload;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.LedgerConfigurationPayloadProvider;
import org.junit.jupiter.api.Test;

class GetLedgerConfigurationHandlerTest {

    private static final Bytes SERVICE_ADDRESS = Bytes.wrap(new byte[] {0x12, 0x34});

    private static ClprLedgerConfigurationResponse response() {
        final var qbftPayload = QbftLedgerConfigurationPayload.newBuilder()
                .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                        .protocolVersion(1)
                        .chainId("eip155:1337")
                        .serviceAddress(SERVICE_ADDRESS)
                        .build())
                .epochLength(30_000L)
                .build();
        return ClprLedgerConfigurationResponse.newBuilder().qbft(qbftPayload).build();
    }

    @Test
    void handle_passesConfiguredCommitmentLevelToProvider() {
        final var levelCapture = new AtomicReference<CommitmentLevel>();
        final var expectedResponse = response();
        final LedgerConfigurationPayloadProvider provider = level -> {
            levelCapture.set(level);
            return expectedResponse;
        };

        final var handler = new GetLedgerConfigurationHandler(serviceAddress -> provider, CommitmentLevel.FINALIZED);
        final var result =
                handler.handle(ClprGetLedgerConfigurationRequest.newBuilder().build());

        assertThat(result).isSameAs(expectedResponse);
        assertThat(levelCapture.get()).isEqualTo(CommitmentLevel.FINALIZED);
    }

    @Test
    void handle_routesBySelectedServiceAddress() {
        final var addressCapture = new AtomicReference<Bytes>();
        final var expectedResponse = response();
        final var handler = new GetLedgerConfigurationHandler(
                serviceAddress -> {
                    addressCapture.set(serviceAddress);
                    return level -> expectedResponse;
                },
                CommitmentLevel.FINALIZED);

        final var result = handler.handle(ClprGetLedgerConfigurationRequest.newBuilder()
                .serviceAddress(SERVICE_ADDRESS)
                .build());

        assertThat(result).isSameAs(expectedResponse);
        assertThat(addressCapture.get()).isEqualTo(SERVICE_ADDRESS);
    }

    @Test
    void handle_emptySelectorWithNoProviderReturnsUnimplemented() {
        final var handler = new GetLedgerConfigurationHandler(serviceAddress -> null, CommitmentLevel.FINALIZED);

        assertThatThrownBy(() -> handler.handle(
                        ClprGetLedgerConfigurationRequest.newBuilder().build()))
                .isInstanceOf(GrpcException.class)
                .extracting(e -> ((GrpcException) e).status())
                .isEqualTo(GrpcStatus.UNIMPLEMENTED);
    }

    @Test
    void handle_unknownServiceAddressReturnsNotFound() {
        final var handler = new GetLedgerConfigurationHandler(serviceAddress -> null, CommitmentLevel.FINALIZED);

        assertThatThrownBy(() -> handler.handle(ClprGetLedgerConfigurationRequest.newBuilder()
                        .serviceAddress(SERVICE_ADDRESS)
                        .build()))
                .isInstanceOf(GrpcException.class)
                .extracting(e -> ((GrpcException) e).status())
                .isEqualTo(GrpcStatus.NOT_FOUND);
    }

    @Test
    void handle_propagatesProviderFailure() {
        final LedgerConfigurationPayloadProvider failing = level -> {
            throw new IllegalStateException("rpc down");
        };
        final var handler = new GetLedgerConfigurationHandler(serviceAddress -> failing, CommitmentLevel.FINALIZED);

        assertThatThrownBy(() -> handler.handle(
                        ClprGetLedgerConfigurationRequest.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rpc down");
    }
}
