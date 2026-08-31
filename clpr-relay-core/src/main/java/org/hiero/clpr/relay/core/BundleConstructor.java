// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Constructs proofs from EVM state that can be submitted to the Hiero network.
 *
 * <p>Implementations observe state changes and produce proof bytes suitable for
 * on-chain verification by the CLPR EVM Verifier.
 */
public interface BundleConstructor {

    /**
     * Returns the most recent bundle payload (data and proof) for the given channel, if available.
     *
     * @param channelId the CLPR channel identifier
     * @return an {@link Optional} containing the bundle payload bytes, or empty if none is available
     */
    @NonNull
    Optional<Bytes> getLatestBundlePayload(@NonNull final Bytes channelId);

    /**
     * Notifies this constructor that the on-chain state has changed.
     *
     * <p>Implementations should use the new state to update any cached proofs.
     *
     * @param blockNumber the block number against which the state was read
     * @param channelId the CLPR channel identifier
     * @param channelState the updated channel state
     * @param pendingMessages the list of pending messages for this channel
     */
    void onStateChanged(
            @NonNull final BigInteger blockNumber,
            @NonNull final Bytes channelId,
            @NonNull final ClprChannel channelState,
            @NonNull final List<ContractStateReader.QueuedMessage> pendingMessages);
}
