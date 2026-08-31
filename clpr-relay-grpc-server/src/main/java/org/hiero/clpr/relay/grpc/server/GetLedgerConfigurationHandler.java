// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import com.hedera.hapi.node.state.clpr.ClprGetLedgerConfigurationRequest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfigurationResponse;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.LedgerConfigurationPayloadProvider;
import org.jspecify.annotations.Nullable;

/**
 * Serves inbound {@code getLedgerConfiguration} RPCs.
 *
 * <p>Returns the ledger configuration of the local deployment selected by the request's
 * {@link ClprGetLedgerConfigurationRequest#serviceAddress()}, read at a fixed commitment level.
 * An empty {@code service_address} selects the endpoint's single (or primary) local
 * configuration, so callers that send no selector remain supported.
 *
 * <p>The response is a {@link ClprLedgerConfigurationResponse} wrapper whose {@code qbft} field
 * is set for QBFT chains; callers inspect which field is non-null to determine the source
 * chain's consensus type.
 */
public final class GetLedgerConfigurationHandler {

    /**
     * Resolves the {@link LedgerConfigurationPayloadProvider} that serves a requested
     * {@code service_address}.
     */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param serviceAddress the requested ClprService address; {@link Bytes#EMPTY} when the
         *                       caller sent no selector, for which the single (or primary) provider
         *                       is returned
         * @return the provider serving that address, or {@code null} if none is registered
         */
        @Nullable
        LedgerConfigurationPayloadProvider resolve(Bytes serviceAddress);
    }

    private final Resolver resolver;
    private final CommitmentLevel commitmentLevel;

    /**
     * @param resolver        maps a requested {@code service_address} to its provider
     * @param commitmentLevel the commitment level at which the ledger state is read
     */
    public GetLedgerConfigurationHandler(final Resolver resolver, final CommitmentLevel commitmentLevel) {
        this.resolver = resolver;
        this.commitmentLevel = commitmentLevel;
    }

    /**
     * Handle a {@code getLedgerConfiguration} request.
     *
     * @param request the request payload; its {@code service_address} selects the target deployment
     * @return the response wrapper with the appropriate payload field set
     * @throws GrpcException {@link GrpcStatus#NOT_FOUND} when a non-empty {@code service_address}
     *                       matches no registered deployment; {@link GrpcStatus#UNIMPLEMENTED} when
     *                       the field is empty and no deployment is registered yet
     */
    public ClprLedgerConfigurationResponse handle(final ClprGetLedgerConfigurationRequest request) {
        final Bytes serviceAddress = request.serviceAddress();
        final LedgerConfigurationPayloadProvider provider = resolver.resolve(serviceAddress);
        if (provider == null) {
            if (serviceAddress.length() == 0) {
                // Empty selector and nothing registered (e.g. a discovery-only relay before its
                // first channel is discovered). Preserve the pre-existing gRPC semantics — the
                // handler used to be null in this case and callers got UNIMPLEMENTED — rather than
                // letting an unchecked exception surface as INTERNAL and provoke retry storms.
                throw new GrpcException(
                        GrpcStatus.UNIMPLEMENTED, "getLedgerConfiguration is not available: no channel registered yet");
            }
            throw new GrpcException(
                    GrpcStatus.NOT_FOUND,
                    "getLedgerConfiguration: no ledger configuration registered for service address 0x"
                            + serviceAddress.toHex());
        }
        return provider.provide(commitmentLevel);
    }
}
