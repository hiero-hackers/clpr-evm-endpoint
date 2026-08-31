// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;

/**
 * A stub implementation of {@link BundleConstructor} for use during development and testing.
 *
 * <p>This implementation tags proof bytes with a magic prefix ({@code "CLPRSTUB"}) and
 * serializes the state using PBJ-generated types. It allows the full sync flow to be
 * exercised end-to-end before real cryptographic proof logic is implemented.
 */
public final class StubBundleConstructor implements BundleConstructor {

    /** Magic prefix bytes that tag all proofs produced by this stub implementation. */
    static final Bytes MAGIC_PREFIX = Bytes.wrap("CLPRSTUB".getBytes());

    /** Cache of the latest bundle proof per channel ID. */
    private final ConcurrentHashMap<Bytes, Bytes> bundleProofCache = new ConcurrentHashMap<>();

    @Override
    @NonNull
    public Optional<Bytes> getLatestBundlePayload(@NonNull final Bytes channelId) {
        return Optional.ofNullable(bundleProofCache.get(channelId));
    }

    @Override
    public void onStateChanged(
            @NonNull final BigInteger blockNumber,
            @NonNull final Bytes channelId,
            @NonNull final ClprChannel channelState,
            @NonNull final List<ContractStateReader.QueuedMessage> pendingMessages) {

        final var metadata = buildQueueMetadata(channelState);

        // Extract payloads from message values
        final List<ClprMessagePayload> payloads = pendingMessages.stream()
                .map(ContractStateReader.QueuedMessage::value)
                .map(ClprMessageValue::payload)
                .toList();

        // Build bundle content and cache the bundle proof
        final ClprBundleContent content = ClprBundleContent.newBuilder()
                .metadata(metadata)
                .messages(payloads)
                .build();
        final Bytes serializedContent = ClprBundleContent.PROTOBUF.toBytes(content);
        bundleProofCache.put(channelId, prependMagicPrefix(serializedContent));
    }

    /**
     * Builds {@link ClprQueueMetadata} from the given channel state.
     *
     * @param channel the current channel state
     * @return the queue metadata derived from the channel
     */
    private static ClprQueueMetadata buildQueueMetadata(@NonNull final ClprChannel channel) {
        return ClprQueueMetadata.newBuilder()
                .nextMessageId(channel.nextMessageId())
                .sentRunningHash(channel.sentRunningHash())
                .receivedMessageId(channel.receivedMessageId())
                .receivedRunningHash(channel.receivedRunningHash())
                .status(channel.status())
                .trustAnchorId(channel.trustAnchorId())
                .endpointManifestVersion(channel.endpointManifestVersion())
                .build();
    }

    /**
     * Prepends the magic prefix to the given serialized bytes.
     *
     * @param serialized the data to prefix
     * @return a new {@link Bytes} instance containing the prefix followed by the data
     */
    private static Bytes prependMagicPrefix(@NonNull final Bytes serialized) {
        final byte[] prefixBytes = MAGIC_PREFIX.toByteArray();
        final byte[] dataBytes = serialized.toByteArray();
        final byte[] combined = new byte[prefixBytes.length + dataBytes.length];
        System.arraycopy(prefixBytes, 0, combined, 0, prefixBytes.length);
        System.arraycopy(dataBytes, 0, combined, prefixBytes.length, dataBytes.length);
        return Bytes.wrap(combined);
    }
}
