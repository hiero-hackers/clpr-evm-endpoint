// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-channel record of the endpoint-manifest version a peer most recently reported holding of
 * <em>this</em> endpoint's manifest, taken from the received
 * {@code ClprQueueMetadata.endpoint_manifest_version} of each inbound sync.
 *
 * <p>The outbound bundle constructor consults this to decide whether to attach a local
 * endpoint-manifest proof: it attaches one only when the local manifest version is strictly greater
 * than the peer's known version, so a peer that is already current is not re-sent a manifest it
 * would silently skip (spec §4.2 Step 1b). An unknown channel defaults to version {@code 0} (the
 * peer has told us nothing yet), which makes the constructor attach the proof on the first sync and
 * is self-correcting.
 *
 * <p>Shared between the (global) inbound sync handler, which records versions, and the
 * per-channel bundle constructors, which read them; backed by a {@link ConcurrentHashMap} keyed
 * by channel id. Recorded versions are folded with {@code max} so an out-of-order inbound sync
 * can never lower a peer's known version.
 */
public final class PeerManifestVersionCache {

    private final ConcurrentHashMap<Bytes, Long> versions = new ConcurrentHashMap<>();

    /**
     * Record the manifest version a peer reported (of this endpoint's manifest) for the given
     * channel. Monotonic: a lower version than one already seen is ignored.
     *
     * @param channelId the CLPR channel identifier
     * @param version      the peer's reported {@code endpoint_manifest_version}
     */
    public void record(final Bytes channelId, final long version) {
        versions.merge(channelId, version, Math::max);
    }

    /**
     * The highest manifest version this channel's peer has reported holding of our manifest, or
     * {@code 0} if no inbound sync has been recorded yet.
     *
     * @param channelId the CLPR channel identifier
     * @return the peer's known manifest version, or {@code 0} when unknown
     */
    public long knownVersion(final Bytes channelId) {
        return versions.getOrDefault(channelId, 0L);
    }
}
