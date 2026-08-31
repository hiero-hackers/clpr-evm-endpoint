// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Optional;

/**
 * Narrow read role: resolve a single CLPR Channel's state by id at a given block.
 *
 * <p>Split out of {@link ContractStateReader} (issue #291) so consumers that only need channel
 * lookup — the inbound {@code ClprSyncHandler}, for one — depend on this one method rather than the
 * whole reader surface. {@link ContractStateReader} extends this interface, so every full reader is
 * also a {@code ChannelLookup}; a routing adapter that can answer nothing else can implement just
 * this role.
 */
public interface ChannelLookup {

    /**
     * Reads the channel state for the given channel identifier.
     *
     * <p>Returns {@link Optional#empty()} if the contract has no Channel record for the id —
     * i.e. {@code getChannel} reverts with {@code ClprChannelNotFound()}. Per spec §3.1.3
     * that maps to two indistinguishable situations: the id is in the PENDING phase (commitment
     * registered, {@code completeChannel} not yet called), or it was never registered. Any
     * other RPC-layer or protocol failure is propagated as an exception — callers must not treat
     * those as "no record".
     *
     * @param channelId the CLPR channel identifier
     * @param blockTag the block to read against - hex number of tag ("latest", "safe", etc)
     * @return the channel state, or {@code Optional.empty()} if no record exists
     */
    Optional<ClprChannel> readChannelState(final Bytes channelId, final String blockTag);
}
