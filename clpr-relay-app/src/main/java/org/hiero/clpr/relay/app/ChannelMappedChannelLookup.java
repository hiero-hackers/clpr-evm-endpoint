// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Optional;
import java.util.function.Function;
import org.hiero.clpr.relay.core.ChannelLookup;

/**
 * Routes {@link ChannelLookup#readChannelState} to the {@link ClprChannelHandler} resolved
 * for the channel id, presenting the whole channel topology as the single-method
 * {@link ChannelLookup} role the inbound {@code ClprSyncHandler} consumes.
 *
 * <p>Replaces the former full-{@code ContractStateReader} adapter (issue #291): the sync handler
 * only ever looks up channel state, so routing just that one method removes the adapter's
 * previously-unroutable methods (the throwing {@code readLedgerConfiguration}, the dead peer-endpoint
 * roster read) instead of forcing them onto a type that can never satisfy them.
 */
final class ChannelMappedChannelLookup implements ChannelLookup {

    private final Function<Bytes, Optional<ClprChannelHandler>> lookup;

    ChannelMappedChannelLookup(final Function<Bytes, Optional<ClprChannelHandler>> lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<ClprChannel> readChannelState(final Bytes channelId, final String blockTag) {
        return lookup.apply(channelId).flatMap(h -> h.stateReader().readChannelState(channelId, blockTag));
    }
}
