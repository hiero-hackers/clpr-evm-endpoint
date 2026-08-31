// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Optional;
import java.util.function.Function;
import org.hiero.clpr.relay.core.ParsedBundle;
import org.hiero.clpr.relay.core.TransactionSubmitter;

/**
 * Routes each {@link TransactionSubmitter} call to the {@link ClprChannelHandler} resolved for
 * the channel id, presenting the whole channel topology as a single {@link TransactionSubmitter}
 * to {@code ClprSyncHandler}. The handler is resolved at call time, so channels registered after
 * this adapter was built (e.g. discovered on-chain) are served immediately.
 */
final class ChannelMappedTransactionSubmitter implements TransactionSubmitter {

    private final Function<Bytes, Optional<ClprChannelHandler>> lookup;

    ChannelMappedTransactionSubmitter(final Function<Bytes, Optional<ClprChannelHandler>> lookup) {
        this.lookup = lookup;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Dispatches to the submitter owned by {@code channel}'s handler.
     *
     * @throws IllegalStateException if no handler is registered for that channel id
     */
    @Override
    public void submitBundle(final ClprChannel channel, final ParsedBundle verified) {
        handlerFor(channel.channelId()).txSubmitter().submitBundle(channel, verified);
    }

    private ClprChannelHandler handlerFor(final Bytes channelId) {
        return lookup.apply(channelId)
                .orElseThrow(() ->
                        new IllegalStateException("No channel handler registered for channelId " + channelId.toHex()));
    }
}
