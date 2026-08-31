// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;

/**
 * Global registry of peer TLS CA certificates, keyed by channel. Updated whenever the on-chain
 * peer roster changes for a channel; queried on every mTLS handshake to validate an incoming
 * client certificate and to resolve peer identity on authenticated sync RPCs.
 *
 * <p>Entries are keyed by {@code channelId}: a call to {@link #update} replaces the full cert
 * set for that channel atomically. New channels register themselves at bootstrap; existing
 * channels refresh on every on-chain roster advance detected by the state-change poller.
 *
 * <p>Lookups iterate all registered channels, so the trust check is always evaluated against the
 * union of all active channel rosters — necessary because an incoming mTLS handshake carries no
 * channel identity before the TLS layer completes.
 *
 * <p>Positive match results are memoised: the ECDSA verification is paid at most once per
 * distinct leaf certificate. Negative results are intentionally not cached — memoising misses
 * would allow a hostile client presenting many distinct self-signed certs to grow the cache
 * without bound. All methods are thread-safe; a call to {@link #update} is immediately visible
 * to subsequent {@link #matchByCa} callers.
 */
public final class PeerEndpointTlsRegistry {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private final HashMap<Bytes, List<PeerRosterEntry>> byChannel = new HashMap<>();

    private final ConcurrentHashMap<X509Certificate, Optional<ClprEndpoint>> matchCache = new ConcurrentHashMap<>();

    /**
     * Replace the peer cert set for {@code channelId} with entries parsed from {@code roster}.
     * An empty {@code roster} clears that channel's entry.
     *
     * @param channelId the channel whose peer roster changed
     * @param roster       the new authoritative roster from the on-chain contract
     */
    public void update(final Bytes channelId, final Collection<ClprEndpoint> roster) {
        final var entries = new ArrayList<PeerRosterEntry>(roster.size());
        for (final ClprEndpoint endpoint : roster) {
            entries.add(PeerRosterEntry.parse(endpoint));
        }
        final Lock writeLock = rwLock.writeLock();
        writeLock.lock();
        try {
            byChannel.put(channelId, List.copyOf(entries));
            matchCache.clear();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Find the peer endpoint whose on-chain CA certificate issued {@code leaf}.
     *
     * <p>Iterates all registered channels. Returns the first match found; the order across
     * channels is not guaranteed (concurrent map iteration), but a given leaf can chain to at
     * most one CA, so the result is deterministic even if the iteration order is not.
     *
     * @param leaf the client leaf certificate presented at the TLS handshake, or {@code null}
     * @return the matching peer endpoint, or empty if none match or {@code leaf} is {@code null}
     */
    public Optional<ClprEndpoint> matchByCa(@Nullable final X509Certificate leaf) {
        if (leaf == null) {
            return Optional.empty();
        }
        final Lock readLock = rwLock.readLock();
        readLock.lock();
        try {
            final var cached = matchCache.get(leaf);
            if (cached != null) return cached;
            final var result = scanByCa(leaf);
            if (result.isPresent()) matchCache.put(leaf, result);
            return result;
        } finally {
            readLock.unlock();
        }
    }

    private Optional<ClprEndpoint> scanByCa(final X509Certificate leaf) {
        for (final List<PeerRosterEntry> entries : byChannel.values()) {
            final var match = Certs.matchRosterByCa(leaf, entries);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }
}
