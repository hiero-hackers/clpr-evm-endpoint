// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprGetLedgerConfigurationRequest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import org.hiero.clpr.relay.grpc.client.testfixtures.ClprEndpointInfoClient;
import org.junit.jupiter.api.Test;

class ClprEndpointInfoClientTest {

    private static ClprGetLedgerConfigurationRequest emptyRequest() {
        return ClprGetLedgerConfigurationRequest.newBuilder()
                .serviceAddress(Bytes.EMPTY)
                .build();
    }

    @Test
    void nonPositiveMaxMessageSize_throws() {
        assertThatThrownBy(() -> new ClprEndpointInfoClient(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Regression: after {@link ClprEndpointInfoClient#close()} a caller must not be able to build and
     * cache a fresh channel — otherwise that channel outlives the drain and grpc-java's orphan detector
     * logs a {@code SEVERE} when it is garbage collected. The call must fail fast (no network attempt).
     */
    @Test
    void callAfterClose_throws() {
        final ClprEndpointInfoClient client = new ClprEndpointInfoClient();
        client.close();

        assertThatThrownBy(() -> client.getLedgerConfiguration("127.0.0.1", 1, emptyRequest()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void close_isIdempotentAndSafeOnEmptyClient() {
        final var client = new ClprEndpointInfoClient(Duration.ofSeconds(5));
        assertThatCode(client::close).doesNotThrowAnyException();
        assertThatCode(client::close).doesNotThrowAnyException();
    }
}
