// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprMerklePathView;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprStateProofView;
import com.hedera.hapi.node.state.clpr.ClprStateValueView;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the Hiero proof format: a serialised {@code com.hedera.hapi.block.stream.StateProof}, as
 * produced by a Hiero consensus node's CLPR sync responder ({@code ClprStateProofManager}).
 *
 * <p>The spec leaves {@code bundle_payload} opaque ("format is verifier-specific"); a Hiero node emits
 * its native block-stream {@code StateProof} — a list of Merkle paths from the signed block root to each
 * proven leaf, plus a TSS block signature. This handler performs <b>no</b> cryptographic verification
 * (that is the on-chain verifier contract's job). It extracts the two leaf kinds the relay needs: the
 * {@code ClprChannel} leaf (from which the queue metadata is reconstructed) and each
 * {@code ClprMessageValue} leaf (whose payloads form the bundle's message list).
 *
 * <p>Decoding goes through {@link ClprStateProofView} — a minimal generated "lens" that declares only
 * the subset of the {@code StateProof} tree we traverse. PBJ parses non-strict by default, so every
 * other field (the TSS proof, sibling hashes, the other {@code StateValue} variants, …) is skipped
 * rather than fatal, keeping the decode tolerant of Hiero-side schema growth while staying fully
 * type-safe.
 */
public final class HieroProofCodec implements BundlePayloadCodec {
    @Override
    public ParsedBundle decodeBundle(final Bytes proofBytes) {
        try {
            return decode(proofBytes);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Failed to parse Hiero bundle proof", e);
        }
    }

    @Override
    public ParsedBundle parseBundle(final Bytes proofBytes, final long receivedMessageId) {
        // Read-only format: dropping already-received messages would mean removing ClprMessageValue leaf
        // paths and re-serialising the block-stream StateProof, but this module decodes through the
        // read-only ClprStateProofView lens and does not depend on the full StateProof type. So the
        // bundle is returned unchanged; already-received messages are left for the on-chain replay guard.
        return decodeBundle(proofBytes);
    }

    private static ParsedBundle decode(final Bytes proofBytes) throws ParseException {
        // Non-strict parse (PBJ default): fields the view doesn't declare — the TSS signed_block_proof,
        // sibling hashes, StateItem.key, the other StateValue variants — are skipped, not fatal.
        final ClprStateProofView view = ClprStateProofView.PROTOBUF.parse(proofBytes);

        ClprChannel channel = null;
        final List<ClprMessagePayload> messages = new ArrayList<>();
        for (final ClprMerklePathView path : view.paths()) {
            if (!path.hasStateItemLeaf() || !path.stateItemLeaf().hasValue()) {
                continue;
            }
            final ClprStateValueView value = path.stateItemLeaf().value();
            if (value.hasClprChannel()) {
                channel = value.clprChannel();
            } else if (value.hasClprMessageValue()) {
                messages.add(value.clprMessageValue().payload());
            }
        }

        if (channel == null) {
            throw new IllegalArgumentException("Hiero StateProof contained no CLPR channel leaf");
        }

        // nextMessageId is "one past the last message in THIS bundle": the responder includes messages
        // starting at ackedMessageId + 1, so the bundle frontier is acked + 1 + (messages included).
        final ClprQueueMetadata metadata = ClprQueueMetadata.newBuilder()
                .nextMessageId(channel.ackedMessageId() + 1L + messages.size())
                .sentRunningHash(channel.sentRunningHash())
                .receivedMessageId(channel.receivedMessageId())
                .receivedRunningHash(channel.receivedRunningHash())
                .status(channel.status())
                .trustAnchorId(channel.trustAnchorId())
                // The version of THIS endpoint's manifest the Hiero peer holds (from its channel
                // leaf). Carrying it into the parsed metadata lets ClprSyncHandler record it, so our
                // outbound QBFT constructor stops re-attaching the manifest proof once the peer is
                // current (spec §4.2 Step 1b). Omitting it pins the recorded version at 0 for every
                // Hiero peer — read as "peer always behind" — and re-sends the proof on every bundle.
                .endpointManifestVersion(channel.endpointManifestVersion())
                .build();
        return new ParsedBundle(metadata, List.copyOf(messages), proofBytes);
    }
}
