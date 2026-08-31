// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Optional;
import org.hiero.clpr.relay.core.BundleConstructor;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.core.ManifestProofPolicy;
import org.hiero.clpr.relay.core.PeerManifestVersionCache;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Cross-codec contract for the Step-3 endpoint-manifest proof attachment (spec §4.2 Step 1b).
 *
 * <p>Every {@link BundleConstructor} able to carry an endpoint-manifest proof MUST gate the attachment
 * through {@link ManifestProofPolicy} so the "attach iff the local manifest version is strictly ahead
 * of the version the peer holds AND the manifest carries a service address" rule is identical across
 * wire formats. This base pins that behaviour; each codec supplies the three hooks below.
 *
 * <p>A new bundle format (e.g. a future Ethereum proof constructor) plugs in by subclassing this and
 * implementing the hooks — if it forgets to route through the policy, either
 * {@link #attachesManifestWhenLocalStrictlyAheadOfPeer()} or one of the skip cases fails here. That is
 * the forcing function the review of #297 asked for: the decision cannot be re-implemented wrong
 * (it lives only in {@link ManifestProofPolicy}), and a codec's manifest wiring cannot go untested.
 */
public abstract class BundleConstructorManifestContract {

    /** The channel id every hook builds its bundle for (32 zero bytes; matches each codec's fixtures). */
    protected static final Bytes CONN = Bytes.wrap(new byte[32]);

    /**
     * Builds the codec under test wired with {@code manifestReader} as its local-manifest source and
     * {@code peerVersions} as the peer-known-version cache, plus whatever codec-specific RPC stubs are
     * needed to produce exactly one bundle for {@link #CONN} via {@link #driveOneBundle}.
     */
    protected abstract BundleConstructor newConstructor(
            @Nullable ContractStateReader manifestReader, @Nullable PeerManifestVersionCache peerVersions);

    /** Drives a single {@code onStateChanged} that produces a (non-empty) bundle for {@link #CONN}. */
    protected abstract void driveOneBundle(BundleConstructor constructor);

    /** Parses the cached bundle payload and reports whether an endpoint-manifest proof is attached. */
    protected abstract boolean manifestAttached(Bytes payload) throws Exception;

    @Test
    void attachesManifestWhenLocalStrictlyAheadOfPeer() throws Exception {
        // Local manifest v1 with a service address; the peer-known version defaults to 0 → 1 > 0 → attach.
        assertThat(manifestAttached(buildBundle(readerOf(manifest(1L, true)), new PeerManifestVersionCache())))
                .as("a manifest strictly ahead of the peer and carrying a service address must be attached")
                .isTrue();
    }

    @Test
    void skipsManifestWhenPeerAlreadyCurrent() throws Exception {
        final var peerVersions = new PeerManifestVersionCache();
        peerVersions.record(CONN, 1L); // peer already holds v1 → 1 > 1 is false → skip (peer would drop a re-send)
        assertThat(manifestAttached(buildBundle(readerOf(manifest(1L, true)), peerVersions)))
                .as("a manifest the peer already holds must not be re-attached")
                .isFalse();
    }

    @Test
    void skipsManifestWhenServiceAddressEmpty() throws Exception {
        // v1 but an empty service address (freshly initialized ledger, no admitted endpoint) → not provable.
        assertThat(manifestAttached(buildBundle(readerOf(manifest(1L, false)), new PeerManifestVersionCache())))
                .as("a version>=1 manifest with an empty service address must not be attached")
                .isFalse();
    }

    @Test
    void skipsManifestWhenNoLocalManifestReader() throws Exception {
        // No reader → manifest proofs are disabled entirely.
        assertThat(manifestAttached(buildBundle(null, new PeerManifestVersionCache())))
                .as("with no local-manifest reader no manifest proof may be attached")
                .isFalse();
    }

    private Bytes buildBundle(
            @Nullable final ContractStateReader manifestReader, final PeerManifestVersionCache peerVersions) {
        final var constructor = newConstructor(manifestReader, peerVersions);
        driveOneBundle(constructor);
        final Optional<Bytes> payload = constructor.getLatestBundlePayload(CONN);
        assertThat(payload)
                .as("the scenario must produce a bundle so the manifest attachment can be asserted")
                .isPresent();
        return payload.get();
    }

    private static ClprEndpointManifest manifest(final long version, final boolean withServiceAddress) {
        final var builder = ClprEndpointManifest.newBuilder().version(version);
        if (withServiceAddress) {
            builder.serviceAddress(Bytes.wrap(new byte[20]));
        }
        return builder.build();
    }

    private static ContractStateReader readerOf(final ClprEndpointManifest manifest) {
        return new FixedManifestReader(manifest);
    }

    /** Minimal {@link ContractStateReader} that only supplies a fixed local endpoint manifest. */
    private static final class FixedManifestReader implements ContractStateReader {
        private final ClprEndpointManifest manifest;

        FixedManifestReader(final ClprEndpointManifest manifest) {
            this.manifest = manifest;
        }

        @Override
        public ClprEndpointManifest readEndpointManifest(final String blockTag) {
            return manifest;
        }

        @Override
        public Optional<ClprChannel> readChannelState(final Bytes channelId, final String blockTag) {
            return Optional.empty();
        }

        @Override
        public List<QueuedMessage> readQueuedMessages(
                final Bytes channelId, final long fromId, final long toId, final String blockTag) {
            return List.of();
        }

        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(final CommitmentLevel commitmentLevel) {
            return ClprLedgerConfiguration.DEFAULT;
        }
    }
}
