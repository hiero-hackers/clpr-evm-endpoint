// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Shared decision for the Step-3 endpoint-manifest proof attachment: whether an outbound bundle for a
 * channel should carry a fresh proof of <em>this</em> endpoint's local manifest.
 *
 * <p>Extracted so the rule lives in exactly one place rather than being re-implemented per bundle
 * constructor (issue #292). Each proof format still attaches the proof in its own wire shape — QBFT an
 * EIP-1186 storage proof, a future format its own — but they all gate the attachment through
 * {@link #manifestToAttach}, so a new codec cannot silently omit the "peer is behind → send" logic the
 * way one did before (the Hiero-codec {@code endpoint_manifest_version} gap fixed in #259).
 *
 * <p>The rule (spec §4.2 Step 1b): attach only when the local manifest version is strictly greater
 * than the version the peer last reported holding of it (tracked per channel in
 * {@link PeerManifestVersionCache}), <em>and</em> the manifest carries a service address. A peer that
 * is already current would silently skip a re-sent manifest, so re-sending is wasteful; an unknown
 * peer version defaults to {@code 0}, so the proof is sent on the first sync and is self-correcting. A
 * version {@code >= 1} manifest with an <em>empty</em> service address is a freshly initialized ledger
 * whose live manifest holds no admitted endpoint yet; proving it is pointless and is rejected on-chain
 * by {@code _verifyEndpointManifest}'s {@code ManifestServiceAddressMismatch} check (which would stall
 * delivery), so it is skipped until the manifest is populated and its version advances.
 */
public final class ManifestProofPolicy {

    private ManifestProofPolicy() {}

    /**
     * Resolves the local endpoint manifest to attach to the next outbound bundle for
     * {@code channelId}, reading the current local manifest from {@code localManifestReader} and
     * the peer's known version from {@code peerManifestVersions}, or {@link Optional#empty()} when no
     * manifest proof is warranted.
     *
     * @param localManifestReader  reader for the local endpoint manifest; {@code null} disables
     *                             manifest proofs entirely (returns empty)
     * @param peerManifestVersions per-channel record of the version each peer reports holding;
     *                             {@code null} is treated as "unknown for every channel" (version 0)
     * @param channelId         the CLPR channel identifier
     * @param blockTag             the block tag to read the local manifest against — pinned to the same
     *                             block the rest of the bundle proof is anchored to
     * @return the manifest to attach, or empty when the attachment should be skipped
     */
    public static Optional<ClprEndpointManifest> manifestToAttach(
            @Nullable final ContractStateReader localManifestReader,
            @Nullable final PeerManifestVersionCache peerManifestVersions,
            final Bytes channelId,
            final String blockTag) {
        if (localManifestReader == null) {
            return Optional.empty();
        }
        final ClprEndpointManifest manifest = localManifestReader.readEndpointManifest(blockTag);
        final long peerKnownVersion = peerManifestVersions != null ? peerManifestVersions.knownVersion(channelId) : 0L;
        return shouldAttach(manifest, peerKnownVersion) ? Optional.of(manifest) : Optional.empty();
    }

    /**
     * The pure predicate behind {@link #manifestToAttach}: attach the manifest proof iff the manifest
     * is strictly ahead of the peer-known version and carries a service address.
     *
     * @param manifest         the local endpoint manifest
     * @param peerKnownVersion the version the peer last reported holding of it
     * @return {@code true} if the proof should be attached
     */
    public static boolean shouldAttach(final ClprEndpointManifest manifest, final long peerKnownVersion) {
        return manifest.version() > peerKnownVersion
                && manifest.serviceAddress().length() > 0;
    }
}
