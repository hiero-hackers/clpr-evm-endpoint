// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import com.hedera.hapi.node.state.clpr.ClprGetLedgerConfigurationRequest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfigurationResponse;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import java.util.Set;

/**
 * Defines the CLPR endpoint-to-endpoint gRPC service for the relay. This service handles peer-to-peer
 * sync calls between CLPR endpoints on different ledger networks.
 *
 * <p>{@code sync} mirrors the Hiero node's {@code ClprEndpointServiceDefinition}.
 * The EVM relay additionally exposes {@code getLedgerConfiguration}, so peers and tooling
 * can fetch this ledger's CLPR configuration directly via the peer-to-peer service rather
 * than the Hiero-side HAPI Query path (which is not available on the EVM side).
 *
 * <p>The {@code discoverEndpoints} RPC has been removed; endpoint information is now
 * obtained via the on-ledger {@code ClprEndpointManifest} mechanism.
 */
@SuppressWarnings("java:S6548")
public final class ClprEndpointServiceDefinition implements RpcServiceDefinition {

    /** The singleton instance of this class. */
    public static final ClprEndpointServiceDefinition INSTANCE = new ClprEndpointServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> METHODS = Set.of(
            new RpcMethodDefinition<>("sync", ClprSyncPayload.class, ClprSyncPayload.class),
            new RpcMethodDefinition<>(
                    "getLedgerConfiguration",
                    ClprGetLedgerConfigurationRequest.class,
                    ClprLedgerConfigurationResponse.class));

    private ClprEndpointServiceDefinition() {
        // Singleton
    }

    @Override
    public String basePath() {
        return "proto.ClprEndpointService";
    }

    @Override
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return METHODS;
    }
}
