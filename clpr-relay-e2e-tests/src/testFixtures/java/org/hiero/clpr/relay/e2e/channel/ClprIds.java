// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.channel;

import java.nio.charset.StandardCharsets;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;

/**
 * Channel and connector identifiers, plus the commit-reveal values CLPR's registration flow needs.
 *
 * <p>The two ids are read back from the contract via {@code eth_call} rather than recomputed here.
 * Both derivations are consensus-critical — {@code channelId} mixes both chains' CAIP-2 ids in a
 * canonical order together with the operator public key and salt — and a local reimplementation
 * would be one contract change away from silently producing ids that no longer match. Since
 * {@code deriveChannelId} and {@code deriveConnectorId} are exposed precisely so callers can compute
 * an id before registering, asking the contract is both cheaper and drift-proof.
 *
 * <p>The commitments and reveal digests <em>are</em> computed locally: they are plain
 * {@code keccak256} over packed bytes with no contract-side helper to call, and the reveal digest has
 * to be signed off-chain anyway.
 */
public final class ClprIds {

    private ClprIds() {}

    /**
     * Ask the service to derive the channel id.
     *
     * <p>Callable on either side of a channel: the derivation sorts the two chain ids canonically, so
     * chain A (local A, peer B) and chain B (local B, peer A) yield the same value — which is exactly
     * what makes one id identify one channel across two ledgers.
     *
     * @param rpc JSON-RPC client for the chain to ask
     * @param serviceAddress that chain's {@code ClprService}
     * @param peerChainId the peer's CAIP-2 id, e.g. {@code eip155:31338}
     * @param pubKey64 operator public key, 64 bytes uncompressed
     * @param salt32 operator-chosen 32-byte label
     * @return the 32-byte channel id
     */
    public static byte[] deriveChannelId(
            final EvmJsonRpcClient rpc,
            final String serviceAddress,
            final String peerChainId,
            final byte[] pubKey64,
            final byte[] salt32) {
        final byte[] chainIdBytes = peerChainId.getBytes(StandardCharsets.UTF_8);
        // Head: [0] offset to peerChainId (string), [1] offset to pubKey (bytes), [2] salt (inline).
        final long headSize = 3L * 32L;
        final byte[] tailChainId =
                AbiCodec.concat(AbiCodec.encodeUint(chainIdBytes.length), AbiCodec.padRight32(chainIdBytes));
        final byte[] tailPubKey = AbiCodec.concat(AbiCodec.encodeUint(pubKey64.length), AbiCodec.padRight32(pubKey64));

        final byte[] callData = AbiCodec.concat(
                AbiCodec.functionSelector("deriveChannelId(string,bytes,bytes32)"),
                AbiCodec.encodeUint(headSize),
                AbiCodec.encodeUint(headSize + tailChainId.length),
                AbiCodec.encodeBytes32(salt32),
                tailChainId,
                tailPubKey);
        return callForBytes32(rpc, serviceAddress, callData, "deriveChannelId");
    }

    /**
     * Ask the service to derive a connector id.
     *
     * @param rpc JSON-RPC client
     * @param serviceAddress the {@code ClprService}
     * @param channelId the channel the connector belongs to
     * @param pubKey64 operator public key, 64 bytes uncompressed
     * @param salt32 operator-chosen 32-byte label
     * @return the 32-byte connector id
     */
    public static byte[] deriveConnectorId(
            final EvmJsonRpcClient rpc,
            final String serviceAddress,
            final byte[] channelId,
            final byte[] pubKey64,
            final byte[] salt32) {
        // Head: [0] channelId (inline), [1] offset to pubKey (bytes), [2] salt (inline).
        final long headSize = 3L * 32L;
        final byte[] callData = AbiCodec.concat(
                AbiCodec.functionSelector("deriveConnectorId(bytes32,bytes,bytes32)"),
                AbiCodec.encodeBytes32(channelId),
                AbiCodec.encodeUint(headSize),
                AbiCodec.encodeBytes32(salt32),
                AbiCodec.encodeUint(pubKey64.length),
                AbiCodec.padRight32(pubKey64));
        return callForBytes32(rpc, serviceAddress, callData, "deriveConnectorId");
    }

    /**
     * Commit value for {@code registerChannel}: {@code keccak256(channelId || pubKey64)}.
     *
     * @param channelId the 32-byte channel id
     * @param pubKey64 operator public key
     * @return the 32-byte commitment
     */
    public static byte[] channelCommitment(final byte[] channelId, final byte[] pubKey64) {
        return AbiCodec.Keccak256.keccak256(AbiCodec.concat(channelId, pubKey64));
    }

    /**
     * Commit value for {@code registerConnector}: {@code keccak256(connectorId || pubKey64)}.
     *
     * @param connectorId the 32-byte connector id
     * @param pubKey64 operator public key
     * @return the 32-byte commitment
     */
    public static byte[] connectorCommitment(final byte[] connectorId, final byte[] pubKey64) {
        return AbiCodec.Keccak256.keccak256(AbiCodec.concat(connectorId, pubKey64));
    }

    /**
     * Digest signed to reveal a channel or connector registration:
     * {@code keccak256(abi.encodePacked(bytes32 id, address service))}.
     *
     * <p>Sign it with {@code EthSigner.signMessage}, which applies the EIP-191 personal-sign prefix —
     * the contract recovers with that same prefix, so signing the bare digest would fail recovery.
     *
     * @param id32 the channel or connector id
     * @param serviceAddress the {@code ClprService} address the registration is bound to
     * @return the 32-byte digest to sign
     */
    public static byte[] revealDigest(final byte[] id32, final String serviceAddress) {
        // encodePacked: 32 bytes of id followed by the 20 raw address bytes — not ABI-padded.
        final byte[] addressBytes = AbiCodec.fromHex(serviceAddress);
        if (addressBytes.length != 20) {
            throw new IllegalArgumentException("expected a 20-byte address, got " + addressBytes.length + " bytes");
        }
        return AbiCodec.Keccak256.keccak256(AbiCodec.concat(id32, addressBytes));
    }

    private static byte[] callForBytes32(
            final EvmJsonRpcClient rpc, final String serviceAddress, final byte[] callData, final String what) {
        final String result = rpc.ethCall(serviceAddress, AbiCodec.toHex(callData), "latest");
        final byte[] decoded = AbiCodec.fromHex(result);
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    what + " on " + serviceAddress + " returned " + decoded.length + " bytes, expected 32: " + result);
        }
        return AbiCodec.decodeBytes32(decoded, 0);
    }
}
