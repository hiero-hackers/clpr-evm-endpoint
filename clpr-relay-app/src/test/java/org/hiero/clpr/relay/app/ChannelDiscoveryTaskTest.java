// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.clpr.relay.app.ChannelDiscoveryTask.Decision.REGISTER;
import static org.hiero.clpr.relay.app.ChannelDiscoveryTask.Decision.SKIP_CLOSED;
import static org.hiero.clpr.relay.app.ChannelDiscoveryTask.Decision.SKIP_UNSERVABLE_PEER;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelDiscoveryTask#decide(ClprChannel, Function)} — the
 * per-discovered-channel registration filter. Guards two behaviours: (1) a re-surfaced
 * {@code ChannelCompleted} event for an already-CLOSED channel is skipped rather than
 * re-registered every scan window (wasteful churn); and (2) a peer whose proof type cannot be
 * resolved is skipped rather than mis-wired with the wrong codec.
 */
class ChannelDiscoveryTaskTest {

    private static final Bytes CONN = Bytes.wrap(new byte[] {1, 2, 3});

    /**
     * Resolver mirroring {@code RelayInstance.addDiscoveryTask}'s default (no explicit mapping): a
     * null/blank or {@code eip155:} chainId resolves to the local network's type (here QBFT); any
     * other (e.g. {@code hiero:}) is unservable → {@code null}.
     */
    private static final Function<String, ProofType> EVM_DEFAULT_RESOLVER = chainId -> {
        if (chainId == null || chainId.isBlank() || chainId.startsWith("eip155:")) {
            return ProofType.QBFT;
        }
        return null;
    };

    private static ClprChannel channel(final ClprChannelStatus status, final String chainId) {
        return ClprChannel.newBuilder()
                .channelId(CONN)
                .status(status)
                .chainId(chainId)
                .build();
    }

    @Test
    void skipsAlreadyClosedEvmChannel() {
        assertThat(ChannelDiscoveryTask.decide(channel(ClprChannelStatus.CLOSED, "eip155:1337"), EVM_DEFAULT_RESOLVER))
                .isEqualTo(SKIP_CLOSED);
    }

    @Test
    void closedTakesPrecedenceOverPeerResolution() {
        // A CLOSED channel is skipped regardless of peer chain — the status check runs first.
        assertThat(ChannelDiscoveryTask.decide(
                        channel(ClprChannelStatus.CLOSED, "hiero:mainnet"), EVM_DEFAULT_RESOLVER))
                .isEqualTo(SKIP_CLOSED);
    }

    @Test
    void registersActiveEvmChannel() {
        assertThat(ChannelDiscoveryTask.decide(channel(ClprChannelStatus.ACTIVE, "eip155:1337"), EVM_DEFAULT_RESOLVER))
                .isEqualTo(REGISTER);
    }

    @Test
    void skipsPeerWithNoResolvableProofType() {
        // A hiero: peer with no mapping resolves to null → unservable → skipped (not mis-wired).
        assertThat(ChannelDiscoveryTask.decide(
                        channel(ClprChannelStatus.ACTIVE, "hiero:mainnet"), EVM_DEFAULT_RESOLVER))
                .isEqualTo(SKIP_UNSERVABLE_PEER);
    }

    @Test
    void registersMappedNonEvmPeer() {
        // With an explicit chainId -> proofType mapping, a non-EVM (e.g. Hiero) peer is servable.
        final Function<String, ProofType> resolver =
                chainId -> "hiero:mainnet".equals(chainId) ? ProofType.Hiero : null;
        assertThat(ChannelDiscoveryTask.decide(channel(ClprChannelStatus.ACTIVE, "hiero:mainnet"), resolver))
                .isEqualTo(REGISTER);
    }

    @Test
    void failsOpenOnUnreadableRecord() {
        // Null record (state could not be read) → resolver sees a null chainId, falls back to a
        // servable default → register rather than silently drop a valid channel.
        assertThat(ChannelDiscoveryTask.decide(null, EVM_DEFAULT_RESOLVER)).isEqualTo(REGISTER);
    }

    @Test
    void failsOpenOnBlankChainId() {
        // A blank/absent chainId falls back to the servable default and registers.
        assertThat(ChannelDiscoveryTask.decide(channel(ClprChannelStatus.ACTIVE, ""), EVM_DEFAULT_RESOLVER))
                .isEqualTo(REGISTER);
    }
}
