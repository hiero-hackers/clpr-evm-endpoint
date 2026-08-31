# Peer Sync Protocol

How endpoint-to-endpoint synchronization is implemented over gRPC. Spec §1.5 defines the wire shape (`ClprSyncPayload`); this doc describes the relay's runtime behavior around it.

Prerequisite: `docs/README.md`, `docs/architecture.md`.

## RPCs hosted

`clpr-relay-grpc/.../ClprEndpointServiceImpl.java` implements three RPCs:

- **`sync(ClprSyncPayload) → ClprSyncPayload`** — the primary message exchange. Bidirectional in shape: each side sends its proof and receives the other side's proof in the response.
- **`discoverEndpoints(...)`** — bootstrap discovery; lets a peer request the seed endpoint list this relay knows about.
- **`getLedgerConfiguration(...)`** — returns this ledger's current `ClprLedgerConfiguration` (protocol version, chain id, service address, throttles, endpoints) as read from the on-chain ClprService contract at `CommitmentLevel.FINALIZED`, together with a QBFT-specific `qbft_payload` wrapper. Used both for manual channel setup and by end-to-end tests to obtain the data needed to call `completeChannel` on a peer ledger.

  The request's optional `service_address` field selects which local deployment to serve when the relay multiplexes several local chains / ClprService deployments. An empty `service_address` returns the single (or primary) local configuration — preserving backward compatibility with callers that send no selector. A non-empty `service_address` that matches no registered deployment yields `NOT_FOUND`; an empty selector on a relay with no channel registered yet yields `UNIMPLEMENTED`.

  The `qbft_payload` (`QbftLedgerConfigurationPayload`) carries everything an EVM peer's QBFT verifier contract needs to validate the configuration against the source ledger's state, without requiring a separate trust-anchor channel:

  - `genesis_block_header` — RLP-encoded genesis header. The verifier decodes its `extra_data` to obtain the initial QBFT validator address (the `keccak256` of [validator, ClprService address, contract codeHash] is the conventional trust anchor — derived on the verifier side, not pre-computed by the relay).
  - `ledger_configuration` — the same `ClprLedgerConfiguration` as above, embedded so the wrapper is self-contained as a `--config-proof` blob.
  - `current_block_header` — RLP-encoded header at the finalized block whose state was read; its `state_root` is the verification root for the proofs below.
  - `clpr_service_account_proof` — EIP-1186 account proof for the ClprService contract under `current_block_header.state_root`. **Empty in the current release**; populated in a follow-up that wires `eth_getProof`.
  - `clpr_service_storage_proofs` — EIP-1186 storage proofs for the ClprService storage slots that compose `ledger_configuration`. **Empty in the current release**; populated alongside the account proof.

  Wiring: `RelayInstance` constructs an `EvmQbftLedgerConfigurationProvider` (implements `core.LedgerConfigurationProvider`) which performs the two `eth_getBlockByNumber` round-trips plus the `ContractStateReader.readLedgerConfiguration` call. The grpc layer's `GetLedgerConfigurationHandler` is a thin pass-through that pins the commitment level (`FINALIZED`).

Server is a Helidon `WebServer` with PBJ routing (`ClprGrpcServer`). Listens on `grpc.port` (default 9545).

## Inbound sync flow

Path: `ClprEndpointServiceImpl → ClprSyncHandler.handleSync`.

For each inbound `ClprSyncPayload`:

1. If `bundlePayload` is non-empty: `BundlePayloadCodec.parseBundle(channelId, payload)` produces a `ParsedBundle`. Failures are logged and the call continues (the peer still needs our reciprocal proof).
2. If parsing succeeded: `TransactionSubmitter.submitBundle(channel, parsed)`. This **enqueues** the bundle on the signing account's `AccountTransactionSubmitter` and returns immediately (the outcome is handled by the submitter's worker and surfaced via metrics). When the worker later processes the request it gates the paid submission on an on-chain `eth_call` preview run at send time (a bundle the contract would reject — stale/replay/etc. — is skipped).
3. Build response: `BundleConstructor.getLatestBundlePayload(channelId)` (cached). Returned to peer in a fresh `ClprSyncPayload`.

The handler is best-effort: a failed inbound submission does not poison the response. The peer will retry on its next sync tick if needed.

## Outbound sync flow

One `ChannelSyncTask` per configured channel, on its own virtual thread. The loop in `executeSyncCycle()`:

1. **Read on-chain state** — `ContractStateReader.readChannelState(channelId, commitmentLevel)`. If status is `CLOSED`, the task self-stops.
2. **Get cached proof** — `BundleConstructor.getLatestBundlePayload(channelId)`. If empty, sleep 500ms and retry.
3. **Pick peer** — `PeerSelector.selectPeer()`. If none, sleep 1s and retry.
4. **Dispatch** — `ClprEndpointClient.sync(ip, port, outboundPayload)` sends the channel id and cached proof to the selected peer.
5. **Process response** — verify the peer's returned bundle. Submit only if it strictly advances our local state:
   - `bundleNextMessageId > localReceivedMessageId + 1` (peer has new messages for us), or
   - `bundleReceivedMessageId > localAckedMessageId` (peer has acknowledged messages we sent).
   - This stale-skip is what avoids `ClprReplayDetected` reverts on the contract.
6. **Record peer outcome** — `PeerSelector.recordSuccess(peer)` or `recordFailure(peer)`.
7. **Sleep** — the task sleeps `syncIntervalMs` before the next iteration. The relay applies `ChannelSyncTask.DEFAULT_INTERVAL_MS` (1000ms) uniformly to every channel (it is no longer per-channel config).

## Peer selection

`PeerSelector` tracks `PeerStats` per peer (`accountId` keyed): consecutive failures, last-success time. Selection priority:

1. Never-failed peers with a recent success.
2. Recent-success peers with non-zero failure count.
3. Round-robin among the remaining (cold or repeatedly-failing) peers.

Source: `clpr-relay-sync/.../PeerSelector.java` (~100 LOC). The candidate peer set comes from `PeerEndpointCache`, which is seeded at startup from the on-chain per-Channel peer endpoint manifest (`getPeerEndpointManifest(channelId)`, read via `readPeerEndpointManifest`) and replaced wholesale by the channel's `EvmChannelStateChangeTask` when the channel's `endpoint_manifest_version` advances (spec §2.4.2). `PeerSelector` reads the cache live, so a refresh takes effect on the next selection; `PeerStats` for a departed peer is left in place (never selected again — see the peer-roster-refresh plan for why this is intentional).

## Proof handling — current state

Proof construction and verification are stubbed today:

- `StubBundleConstructor` builds a placeholder payload from the local channel state.
- `PassThroughCodec` accepts any payload and extracts metadata directly from it.

Real verifier-contract integration (spec §3 "Verification Interfaces") is not yet implemented. When it lands:

- The constructor will assemble a Merkle/state proof against the local chain's `ClprService` storage roots.
- The verifier will call the local verifier contract (the `verifier` field of `ClprChannel`) to authenticate the peer's chain root before accepting messages.
- The wire format of `bundlePayload` is opaque to `clpr-relay-sync` and `clpr-relay-grpc` — those modules need no changes.

The contract layer (`AccountTransactionSubmitter`, `submitBundle(bytes32,bytes)`) is already correct for either stubbed or real proofs because the contract treats the payload as opaque bytes that it dispatches to its own configured verifier.

## Endpoint signing

The relay does **not** sign or verify a per-payload endpoint signature. The `endpoint_signature` field was removed from `ClprSyncPayload`, and the per-endpoint `ecdsa_signing_key` / peer `peer_signing_keys` were removed from the on-chain state (both contract and relay). `ClprSyncPayload` now carries only `channelId` and `bundlePayload`; inbound bundles are accepted on the strength of proof verification (below), not caller authentication.

(a signing key is still used — the per-service `clprServices[].defaultSigningPrivateKeyHex` or a per-channel override — but only to sign the EVM `submitBundle` transaction via `EthSigner`; see `evm-integration.md`. It is unrelated to the removed endpoint signature.)

## Inbound validation and channel-state branching

Before accepting an inbound bundle, `ClprSyncHandler` enforces:

1. **Channel state check** — local channel status is read before attempting submission:
   - `PAUSED`, `CLOSING`, `DRAINED` — skip outbound bundle submission; return reciprocal proof.
   - `CLOSED` — reject the inbound call immediately with an empty-proof response; do not call `submitBundle`.
2. **Bundle preview** — when the `AccountTransactionSubmitter` worker dequeues the bundle, before the paid `submitBundle` transaction it runs a read-only `eth_call` of `ClprService.submitBundle(bytes32,bytes)` at send time (with the relay's address as `from`) that exercises the contract's full validation chain. Any revert skips the submission without spending gas (logged at WARN, counted as `sync.bundle.skipped{reason=rejected}`). Always on; not gated by configuration.

## Metadata exchange (not implemented)

The spec's optional `verifyMetadata(proof)` call (spec §3.11.1) is **not** implemented. Neither clpr-hiero nor clpr-smart-contracts currently supports `MetadataPayload`, so relay-side implementation is blocked on upstream support. Single-call sync (current behavior) is spec-compliant; metadata exchange is an optimization for a future release.

## Concurrency notes

- `PeerEndpointCache` is thread-safe (concurrent map).
- `PeerStats` mutations through `PeerSelector` are synchronized.
- `ChannelSyncTask.running` is volatile; `stop()` flips it and the loop exits at the next iteration boundary.
- Each channel's `EvmChannelStateChangeTask` shares its `PeerEndpointCache` with that channel's `PeerSelector` for endpoint refresh.
