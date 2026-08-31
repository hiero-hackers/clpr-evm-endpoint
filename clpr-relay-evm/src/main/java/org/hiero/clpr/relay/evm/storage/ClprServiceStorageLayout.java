// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.storage;

import static org.hiero.clpr.relay.evm.ByteUtils.leftPad32;
import static org.hiero.clpr.relay.evm.EvmUtils.toUint256Bytes;
import static org.hiero.clpr.relay.evm.EvmUtils.wrapToUint256;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.model.Address;

public final class ClprServiceStorageLayout {
    // TODO: this is quite fragile - any change to CLPR Service types might
    // case the slot to shift. We need a better way to calculate it?
    // TODO: make it configurable
    //
    // Top-level storage slots in ClprServiceStorage. ReentrancyGuardTransient uses EIP-1153
    // transient storage and claims no sequential slot, so protocol fields start at slot 0. The
    // layout is grouped "by change-risk" (hot-path queue first, then packable scalars, then
    // primitive-valued mappings, then mapping-of-struct values, then trailing direct structs):
    //   slot 0     : _authorizedService (address)
    //   slot 1     : _messageQueues       (mapping(bytes32 => mapping(uint64 => MessageValue)))
    //   slot 2     : _bundleDecodeHelper (address) + _clprEnabled (bool)  ← packed
    //   slots 3-4  : _channelCount, _connectorCount
    //   slots 5-14 : primitive-valued mappings (_channelExists, _pendingCommitments, …,
    //                incl. the two deprecated reserved peer-endpoint mappings at slots 13-14)
    //   slot 15    : _channels           (mapping(bytes32 => Channel))
    //   slot 16    : _connectors            (mapping(bytes32 => Connector))
    //   slots 17-21: _endpointManifest      (EndpointManifestState, 5 slots: version@17,
    //                commitment@18, entries@19, liveAccounts@20, liveIndexOneBased@21)
    //   slot 22    : _peerEndpointManifests (mapping(bytes32 => ClprEndpointManifest))
    //   slots 23-30: _config               (LedgerConfiguration)
    //   slot 31    : economicConfig         (EconomicConfig)
    //   (Ownable._owner lives at slot 38, in ClprService's own storage.)
    // Source of truth: ../clpr-smart-contracts/src/ClprServiceStorage.sol + storage-layout.json.
    public static final BigInteger CHANNELS_BASE_SLOT = BigInteger.valueOf(15);
    public static final BigInteger MESSAGE_QUEUES_BASE_SLOT = BigInteger.valueOf(1);

    // Field offsets inside the Solidity {@code ClprTypes.Channel} struct.
    // The struct is laid out as:
    //   slot+0 : bytes32 channelId
    //   slot+1 : address verifier (20B) + ChannelStatus status (1B) + uint64 nextMessageId (8B)
    //   slot+2 : uint64 ackedMessageId + uint64 receivedMessageId + uint64 nextExpectedReplyId
    //   slot+3 : uint96 peerConfigTimestamp + uint96 lastConfigTimestamp
    //   slot+4 : bytes32 sentRunningHash
    //   slot+5 : bytes32 receivedRunningHash
    //   slot+6 : bytes32 ownershipCommitment
    //   slot+7 : bytes32 salt
    //   ...
    //   slot+16: uint64 endpointManifestVersion
    public static final BigInteger CH_OFFSET_STATUS_AND_NEXT_MSG_ID = BigInteger.ONE;
    public static final BigInteger CH_OFFSET_RECEIVED_MSG_ID = BigInteger.TWO;
    public static final BigInteger CH_OFFSET_SENT_RUNNING_HASH = BigInteger.valueOf(4);
    public static final BigInteger CH_OFFSET_RECEIVED_RUNNING_HASH = BigInteger.valueOf(5);
    /** Struct offset for {@code Channel.endpointManifestVersion} (uint64, at slot+16). */
    public static final BigInteger CH_OFFSET_ENDPOINT_MANIFEST_VERSION = BigInteger.valueOf(16);
    /**
     * Storage slot of the {@code commitment} field inside the {@code _endpointManifest} struct.
     * {@code _endpointManifest} base slot = 17; {@code commitment} is the second field (offset 1).
     */
    public static final BigInteger ENDPOINT_MANIFEST_COMMITMENT_SLOT = BigInteger.valueOf(18);

    /**
     * Storage slot of {@code _config.serviceAddress} in {@code ClprServiceStorage}.
     *
     * <p>{@code _config} (a {@code LedgerConfiguration} struct) starts at slot 23 — see the
     * top-level storage-layout comment above. Inside the struct, {@code serviceAddress} is the
     * third field after {@code uint32 protocolVersion} (slot+0) and {@code string chainId}
     * (slot+1); both occupy their own slot because the adjacent dynamic type forces the
     * {@code uint32} to stand alone and the string itself is a dynamic type. So
     * {@code _config.serviceAddress} lives at slot {@code 23 + 2 = 25}.
     *
     * <p>For EVM, {@code serviceAddress} is a 20-byte address stored in a Solidity {@code bytes}
     * field with length &le; 31. Solidity's short-bytes layout stores the value inline in the
     * slot: the 20 left-aligned bytes followed by 11 zero bytes and a final byte holding
     * {@code length * 2 = 40 (0x28)}. A single storage-slot proof therefore covers the full
     * value.
     *
     * <p>Verified against {@code ../clpr-smart-contracts/storage-layout.json}: {@code _config}
     * base = 23, {@code serviceAddress} = 25.
     */
    public static final BigInteger CONFIG_SERVICE_ADDRESS_SLOT = BigInteger.valueOf(25);

    /** ABCI multistore key for the Sei {@code x/evm} module store. */
    public static final Bytes STORE_KEY_EVM = Bytes.wrap("evm".getBytes(StandardCharsets.UTF_8));

    /**
     * Builds the 53-byte Sei ABCI store key for an EVM contract storage slot:
     * {@code 0x03 || 20-byte contract address || 32-byte slot}. The leading {@code 0x03} is the
     * {@code x/evm} module's {@code StateKeyPrefix}; the slot is the raw uint256 (not keccak-hashed).
     *
     * @param clprServiceAddress the contract address the storage slot belongs to
     * @param storageSlot        the raw storage slot index
     * @return the 53-byte ABCI key for {@code /store/evm/key} queries
     */
    public static byte[] buildEvmStorageAbciKey(final Address clprServiceAddress, final BigInteger storageSlot) {
        final var key = new byte[53];
        key[0] = 0x03;
        System.arraycopy(clprServiceAddress.value(), 0, key, 1, 20);
        System.arraycopy(AbiCodec.encodeUint256(storageSlot), 0, key, 21, 32);
        return key;
    }

    public static BigInteger calculateMsgRunningHashStorageSlot(Bytes channelId, BigInteger messageId) {
        // Solidity: keccak256(abi.encode(key, slot))
        // bytes32 key is stored as-is (32 bytes); slot is a uint256 (32 bytes, big-endian)
        final byte[] outerPreimage = new byte[64];
        final byte[] keyBytes = leftPad32(channelId).toByteArray(); // must be exactly 32 bytes
        System.arraycopy(keyBytes, 0, outerPreimage, 0, 32);
        toUint256Bytes(MESSAGE_QUEUES_BASE_SLOT, outerPreimage, 32);

        final BigInteger s1 = new BigInteger(1, AbiCodec.Keccak256.keccak256(outerPreimage));

        // ── Step 2: inner mapping ────────────────────────────────────────────
        // uint64 key is right-aligned (zero-padded on the left) in 32 bytes
        final byte[] innerPreimage = new byte[64];
        toUint256Bytes(messageId, innerPreimage, 0); // fills bytes 0-31
        toUint256Bytes(s1, innerPreimage, 32); // fills bytes 32-63

        final BigInteger s2 = new BigInteger(1, AbiCodec.Keccak256.keccak256(innerPreimage));

        // ── Step 3: struct field offset ──────────────────────────────────────
        // runningHashAfterProcessing is the second field (0-indexed: +1)
        return wrapToUint256(s2.add(BigInteger.ONE));
    }

    /**
     * Computes the storage slot of a field inside {@code _channels[channelId]}.
     *
     * <p>The base struct slot is {@code keccak256(abi.encode(channelId, CHANNELS_BASE_SLOT))};
     * the requested field sits at {@code base + fieldOffset}.
     *
     * @param channelId the 32-byte channel id (left-padded if shorter)
     * @param fieldOffset  the field offset inside the {@code Channel} struct
     * @return the storage slot index
     */
    public static BigInteger calculateChannelFieldStorageSlot(final Bytes channelId, final BigInteger fieldOffset) {
        final byte[] preimage = new byte[64];
        final byte[] keyBytes = leftPad32(channelId).toByteArray(); // must be exactly 32 bytes
        System.arraycopy(keyBytes, 0, preimage, 0, 32);
        toUint256Bytes(CHANNELS_BASE_SLOT, preimage, 32);
        final BigInteger structBase = new BigInteger(1, AbiCodec.Keccak256.keccak256(preimage));
        return wrapToUint256(structBase.add(fieldOffset));
    }
}
