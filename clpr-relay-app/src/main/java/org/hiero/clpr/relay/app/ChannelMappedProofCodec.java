// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Optional;
import java.util.function.Function;
import org.hiero.clpr.relay.core.BundlePayloadCodec;
import org.hiero.clpr.relay.core.BundlePayloadCodecResolver;

/**
 * Resolves the inbound {@link BundlePayloadCodec} for a channel id via the
 * {@link ClprChannelHandler} resolved for that id, throwing if no handler is registered.
 * Resolution happens at call time so channels registered after construction (including those
 * discovered on-chain) are served immediately.
 */
final class ChannelMappedProofCodec implements BundlePayloadCodecResolver {

    private final Function<Bytes, Optional<ClprChannelHandler>> lookup;

    ChannelMappedProofCodec(final Function<Bytes, Optional<ClprChannelHandler>> lookup) {
        this.lookup = lookup;
    }

    @Override
    public BundlePayloadCodec codecFor(final Bytes channelId) {
        return lookup.apply(channelId)
                .map(ClprChannelHandler::inboundCodec)
                .orElseThrow(() ->
                        new IllegalStateException("No channel handler registered for channelId " + channelId.toHex()));
    }
}
