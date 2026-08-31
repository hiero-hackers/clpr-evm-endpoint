// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.channel;

import org.hiero.clpr.relay.e2e.chain.BesuNetwork;
import org.hiero.clpr.relay.e2e.chain.TestAccount;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.jspecify.annotations.Nullable;

/**
 * One CLPR channel spanning two chains.
 *
 * <p>A channel is configuration plus on-chain state, not a process: on each chain there is a
 * {@code Channel} record under the same 32-byte id, and inside each serving endpoint the relay runs
 * two worker loops for it. Declared before {@code startAll()}, wired on chain during it — the wiring
 * needs both chains mining and their contracts deployed, and the endpoints need the channel to exist
 * before they start or they abort it.
 */
public final class Channel {

    private final ChannelSpec spec;
    private final byte[] salt;

    @Nullable
    private byte[] id;

    @Nullable
    private TestAccount operator;

    @Nullable
    private ChannelSide sideA;

    @Nullable
    private ChannelSide sideB;

    Channel(final ChannelSpec spec, final byte[] salt) {
        this.spec = spec;
        this.salt = salt.clone();
    }

    void wired(final byte[] channelId, final TestAccount operator, final ChannelSide a, final ChannelSide b) {
        this.id = channelId.clone();
        this.operator = operator;
        this.sideA = a;
        this.sideB = b;
    }

    /** @return the spec this channel was declared with. */
    public ChannelSpec spec() {
        return spec;
    }

    /**
     * The single operator identity behind this channel on both chains.
     *
     * <p>Shared across the two sides by necessity: the channel id is derived from the operator public
     * key, so two keys would derive two ids and the second {@code completeChannel} would be rejected.
     *
     * @return the operator account
     */
    public TestAccount operator() {
        return require(operator, "operator()");
    }

    /** @return the salt mixed into the channel id. */
    public byte[] salt() {
        return salt.clone();
    }

    /** @return the 32-byte channel id. */
    public byte[] id() {
        return require(id, "id()").clone();
    }

    /** @return the channel id as {@code 0x}-prefixed hex, the form the relay config uses. */
    public String idHex() {
        return AbiCodec.toHex(require(id, "idHex()"));
    }

    /**
     * The channel id in the form the relay's metrics use as the {@code channel_id} label: lowercase
     * hex <b>without</b> the {@code 0x} prefix.
     *
     * <p>Distinct from {@link #idHex()} on purpose. The config file wants the prefixed form and the
     * metric label is unprefixed, and looking a counter up with the wrong one silently returns zero —
     * which reads as "nothing happened" rather than "wrong key".
     *
     * @return the metric label
     */
    public String metricLabel() {
        return java.util.HexFormat.of().formatHex(require(id, "metricLabel()"));
    }

    /** @return the side on {@link ChannelSpec#networkA()}. */
    public ChannelSide a() {
        return require(sideA, "a()");
    }

    /** @return the side on {@link ChannelSpec#networkB()}. */
    public ChannelSide b() {
        return require(sideB, "b()");
    }

    /**
     * The side living on a given chain.
     *
     * @param network one of the channel's two chains
     * @return that side
     * @throws IllegalArgumentException if the chain is not part of this channel
     */
    public ChannelSide sideOn(final BesuNetwork network) {
        if (network.chainId() == spec.networkA().chainId()) {
            return a();
        }
        if (network.chainId() == spec.networkB().chainId()) {
            return b();
        }
        throw new IllegalArgumentException("chain " + network.id() + " is not part of channel " + idHex());
    }

    /**
     * The side opposite the given one.
     *
     * @param side one of the channel's sides
     * @return the other side
     */
    public ChannelSide peerOf(final ChannelSide side) {
        if (side == a()) {
            return b();
        }
        if (side == b()) {
            return a();
        }
        // Silently returning a() for a foreign side would make a mis-wired assertion look like a
        // relay bug; sideOn() validates too.
        throw new IllegalArgumentException("side " + side + " does not belong to channel " + idHex());
    }

    private static <T> T require(@Nullable final T value, final String what) {
        if (value == null) {
            throw new IllegalStateException(
                    what + " is only available after the channel is wired — call E2EEnvironment.startAll() first");
        }
        return value;
    }

    @Override
    public String toString() {
        return id == null ? "Channel[unwired]" : "Channel[" + idHex() + "]";
    }
}
