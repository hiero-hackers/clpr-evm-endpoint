// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.ContractStateReader;

/**
 * Routes {@link BundleConstructor} calls to the {@link ClprChannelHandler} resolved for the
 * channel id, adapting the whole channel topology to the single {@link BundleConstructor}
 * interface consumed by {@code ClprSyncHandler}.
 */
final class ChannelMappedBundleConstructor implements BundleConstructor {

    private final Function<Bytes, Optional<ClprChannelHandler>> lookup;

    ChannelMappedBundleConstructor(final Function<Bytes, Optional<ClprChannelHandler>> lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<Bytes> getLatestBundlePayload(final Bytes channelId) {
        return handlerFor(channelId).bundleConstructor().getLatestBundlePayload(channelId);
    }

    @Override
    public void onStateChanged(
            final BigInteger blockNumber,
            final Bytes channelId,
            final ClprChannel channelState,
            final List<ContractStateReader.QueuedMessage> pendingMessages) {
        handlerFor(channelId).bundleConstructor().onStateChanged(blockNumber, channelId, channelState, pendingMessages);
    }

    private ClprChannelHandler handlerFor(final Bytes channelId) {
        return lookup.apply(channelId)
                .orElseThrow(() ->
                        new IllegalStateException("No channel handler registered for channelId " + channelId.toHex()));
    }
}
