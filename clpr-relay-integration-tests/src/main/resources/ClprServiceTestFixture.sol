// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

// The compiled creation bytecode lives in ClprServiceTestFixture.bin (loaded by
// SeiBundleConstructorIntegrationTest). After editing this file, regenerate it from this
// resources directory with:
//   docker run --rm -v "$PWD":/src ethereum/solc:0.8.28 \
//     --bin --optimize --optimize-runs 200 /src/ClprServiceTestFixture.sol \
//     | awk '/^Binary:/{getline; print}' | tr -d '\n' > ClprServiceTestFixture.bin

// Types copied from ClprTypes (only the subset used by this fixture).

enum ChannelStatus {
    PENDING,
    ACTIVE,
    PAUSED,
    CLOSING,
    DRAINED,
    CLOSED
}

struct Throttles {
    uint64 maxMessagesPerBundle;
    uint64 maxMessagePayloadBytes;
    uint64 maxGasPerMessage;
    uint64 maxQueueDepth;
    uint64 maxSyncBytes;
}

struct Channel {
    // slot 0
    bytes32 channelId;
    // slot 1: verifier (20B) | status (1B) | nextMessageId (8B)
    address verifier;
    ChannelStatus status;
    uint64 nextMessageId;
    // slot 2: ackedMessageId (8B) | receivedMessageId (8B) | nextExpectedReplyId (8B)
    uint64 ackedMessageId;
    uint64 receivedMessageId;
    uint64 nextExpectedReplyId;
    // slot 3: peerConfigTimestamp (12B) | lastConfigTimestamp (12B)
    uint96 peerConfigTimestamp;
    uint96 lastConfigTimestamp;
    // slots 4–7
    bytes32 sentRunningHash;
    bytes32 receivedRunningHash;
    bytes32 ownershipCommitment;
    bytes32 salt;
    // dynamic fields (slots 8+)
    string chainId;           // slot 8
    bytes peerServiceAddress; // slot 9
    Throttles peerThrottles;  // slots 10–11 (5 × uint64 across 2 slots)
    bytes trustAnchor;        // slot 12
    uint64 lastDataMessageId; // slot 13
    bytes trustAnchorId;      // slot 14
    bytes channelContext;     // slot 15
    uint64 endpointManifestVersion; // slot 16
}

struct MessageValue {
    bytes payload;                      // slot +0 (dynamic pointer)
    bytes32 runningHashAfterProcessing; // slot +1
    bytes32 connectorIdForReply;         // slot +2 (inline bytes32)
}

/**
 * Test fixture that replicates the ClprServiceStorage storage layout at the slots
 * that SeiBundleConstructor reads and proves via ABCI queries.
 *
 * Storage assignment (must match ClprServiceStorageLayout.java):
 *   slot  0       : placeholder for _authorizedService (address)
 *   slot  1       : _messageQueues (mapping(bytes32 => mapping(uint64 => MessageValue)))
 *   slots 2 – 14  : 13 × uint256 padding
 *   slot 15       : _channels   (mapping(bytes32 => Channel))
 *
 * Channel struct — relevant storage slots:
 *   +0 : bytes32         channelId
 *   +1 : address verifier (20B) | ChannelStatus status (1B) | uint64 nextMessageId (8B)
 *   +2 : uint64 ackedMessageId (8B) | uint64 receivedMessageId (8B) | uint64 nextExpectedReplyId (8B)
 *   +3 : uint96 peerConfigTimestamp (12B) | uint96 lastConfigTimestamp (12B)
 *   +4 : bytes32 sentRunningHash
 *   +5 : bytes32 receivedRunningHash
 *   +6 : bytes32 ownershipCommitment
 *   +7 : bytes32 salt
 *   +8 onwards: dynamic fields (chainId, peerServiceAddress, peerThrottles, …)
 *
 * MessageValue struct — relevant storage slots:
 *   +0 : bytes   payload                    (dynamic pointer; empty in tests)
 *   +1 : bytes32 runningHashAfterProcessing  ← MUST equal ClprMessageValue.runningHashAfterProcessing()
 *   +2 : bytes32 connectorIdForReply           (inline; zero in tests)
 *
 * Deploy on a Sei devnet, populate via setChannel() / setMessage(), then point
 * SeiBundleConstructor at this contract address.  ABCI queries will return real
 * ICS-23 proofs over whatever values you wrote here.
 */
contract ClprServiceTestFixture {

    // ── Slot 0 ── _authorizedService placeholder ─────────────────────────────
    address private _authorizedServicePlaceholder;

    // ── Slot 1 ── message queues ───────────────────────────────────────────────
    mapping(bytes32 => mapping(uint64 => MessageValue)) private _messageQueues;

    // ── Slots 2–14 ── 13 padding slots to land _channels at slot 15 ──────────
    uint256[13] private _padding;

    // ── Slot 15 ── channel structs ─────────────────────────────────────────────
    mapping(bytes32 => Channel) private _channels;

    // ── Setters ────────────────────────────────────────────────────────────────

    /**
     * Populate or overwrite the fields SeiBundleConstructor reads and proves.
     *
     * @param channelId        bytes32 channel identifier (slot +0)
     * @param verifier            verifier address              (slot +1, bits   0–159)
     * @param status              channel status             (slot +1, bits 160–167)
     * @param nextMessageId       next outbound message id      (slot +1, bits 168–231)
     * @param ackedMessageId      last acked message id         (slot +2, bits   0– 63)
     * @param receivedMessageId   last received message id      (slot +2, bits  64–127)
     * @param sentRunningHash     sent running hash             (slot +4)
     * @param receivedRunningHash received running hash         (slot +5)
     */
    function setChannel(
        bytes32 channelId,
        address verifier,
        ChannelStatus status,
        uint64 nextMessageId,
        uint64 ackedMessageId,
        uint64 receivedMessageId,
        bytes32 sentRunningHash,
        bytes32 receivedRunningHash
    ) external {
        Channel storage c = _channels[channelId];
        c.channelId        = channelId;
        c.verifier            = verifier;
        c.status              = status;
        c.nextMessageId       = nextMessageId;
        c.ackedMessageId      = ackedMessageId;
        c.receivedMessageId   = receivedMessageId;
        c.sentRunningHash     = sentRunningHash;
        c.receivedRunningHash = receivedRunningHash;
    }

    /**
     * Populate or overwrite a message queue entry.
     *
     * The `runningHashAfterProcessing` stored here is compared byte-for-byte
     * by SeiBundleConstructor against `ClprMessageValue.runningHashAfterProcessing()`
     * of the matching pending message.  Pass the same value in both places.
     *
     * @param channelId               bytes32 channel identifier
     * @param messageId                  uint64 message sequence number
     * @param runningHashAfterProcessing the running hash to store at slot +1
     */
    function setMessage(
        bytes32 channelId,
        uint64  messageId,
        bytes32 runningHashAfterProcessing
    ) external {
        _messageQueues[channelId][messageId].runningHashAfterProcessing = runningHashAfterProcessing;
    }
}
