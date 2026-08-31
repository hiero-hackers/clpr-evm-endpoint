// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Resolves the channel-scoped {@link BundlePayloadCodec} for a given channel id.
 *
 * <p>Each {@link BundlePayloadCodec} is bound to one channel at construction (its methods carry no
 * {@code channelId}), so a component that serves many channels holds a resolver rather than a
 * single codec and looks the right one up per request.
 */
@FunctionalInterface
public interface BundlePayloadCodecResolver {

    /**
     * @param channelId the CLPR channel identifier
     * @return the codec bound to that channel
     * @throws IllegalStateException if no codec is registered for {@code channelId}
     */
    BundlePayloadCodec codecFor(final Bytes channelId);
}
