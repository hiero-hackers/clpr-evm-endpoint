# Multi-Chain Support — Design

This document describes how a single relay process supports multiple EVM chains and multiple
`ClprService` deployments. It explains the motivation for each design decision, defines the
key classes, and describes the proto changes that carry consensus-type-specific trust-anchor
material on the wire.

---

## Problem statement

A single-chain relay hard-wires `ContractStateReader`, `TransactionSubmitter`, `BundleConstructor`,
and `BundlePayloadCodec` to one chain, built once at startup and shared across all channels.
The `getLedgerConfiguration` gRPC returns a flat, QBFT-specific payload with no mechanism for a
peer to understand it is talking to a different consensus type.

This prevents a single relay instance from simultaneously maintaining channels to chains
running different consensus algorithms, and prevents peers from knowing which proof format
and trust-anchor material to expect when they call `getLedgerConfiguration`.

---

## Goals

1. A single relay process can manage multiple channels, each potentially targeting a
   different local chain and/or a different `ClprService` deployment.
2. Each channel independently resolves which chain's proof format it must verify from a peer.
3. `getLedgerConfiguration` advertises the source chain's consensus type so peers can route
   the payload to the correct verifier.
4. The channel-scoped work is isolated in one place (`ClprChannelHandler`) so the gRPC
   inbound path reaches it through thin resolver adapters.
5. Adding a further chain type in the future touches the minimum possible surface area.

---

## Approach

### The three-tier hierarchy

The composition mirrors the configuration (`localNetworks[]` → `clprServices[]` → channels):

- **`LocalNetworkAdapter`** — one per `localNetworks[]` entry. Holds the shared clients for a chain
  (the `EvmJsonRpcClient`, the `Eip1559GasStrategy`, and — for a CometBFT network — a
  `CometBftRpcClient`). Built by `LocalNetworkAdapter.create(LocalNetworkConfig)`.
- **`ClprServiceHandler`** — one per `clprServices[]` entry (one deployed `ClprService` contract on
  one local network). Owns its `ChannelDiscoveryTask` (when discovery is enabled) and a
  `Map<Bytes, ClprChannelHandler>`. Resolves each channel's signing key and peer proof type.
- **`ClprChannelHandler`** — one per channel. Owns every channel-scoped component: the
  `EvmContractStateReader`, a per-channel view (`forContract(serviceAddress)`) of the network's
  shared per-account `AccountTransactionSubmitter`, the `BundleConstructor`, the `LedgerConfigurationPayloadProvider`, the `PeerEndpointCache`, the
  `ThrottleEnforcer`, the inbound `BundlePayloadCodec`, and the two worker threads it spawns
  (`ChannelSyncTask` and `EvmChannelStateChangeTask`).

### Channel binding

A channel binds to its chain and peer format as follows:

- **Local chain / service** — determined by the owning `ClprServiceHandler`: its `serviceAddress`
  is the deployed `ClprService` contract, and its `localNetwork` names the `LocalNetworkAdapter`
  the relay submits `submitBundle` transactions through. Multiple `ClprService` deployments on the
  same chain are supported by declaring multiple `clprServices[]` entries on the same `localNetwork`.
- **`peerProofType`** — the `ProofType` of the peer relay's chain, used to select the inbound
  `BundlePayloadCodec`. It is **not** per-channel config: it is resolved at registration time
  from the channel's on-chain peer `chainId` via the `peerProofTypes` map (an `eip155:*` peer
  with no explicit mapping falls back to the local network's `proofType`; an unmapped non-EVM peer
  is unservable and skipped). This resolution is identical for predefined and discovered channels.

### Keeping `ClprSyncHandler` channel-agnostic via resolver adapters

`ClprSyncHandler` is designed around single instances of `ContractStateReader`,
`TransactionSubmitter`, `BundleConstructor`, and `BundlePayloadCodec` (resolved per request by the
`channelId` carried on the payload). Rather than redesigning the gRPC module, four
package-private adapters in `clpr-relay-app` (`ChannelMappedTransactionSubmitter`,
`ChannelMappedContractStateReader`, `ChannelMappedBundleConstructor`,
`ChannelMappedProofCodec`) each hold a `Function<Bytes, Optional<ClprChannelHandler>>` resolver
and dispatch each call to the owning handler's component. The resolver — supplied by `RelayInstance`
— iterates every `ClprServiceHandler` and looks the channel handler up by id, so channels
registered at runtime (discovered on-chain, or predefined channels started later) are served
without rewiring. The `ThrottleEnforcer` (in `ClprSyncHandler`) and `PeerEndpointCache` (in the
`discoverEndpoints` path) are reached the same way, through `Function<Bytes, …>` resolvers.

### Typed `getLedgerConfiguration` response

The `getLedgerConfiguration` gRPC returns a `ClprLedgerConfigurationResponse` wrapper that holds one
variant field per consensus type — exactly one of which is set at runtime. Assembling the payload is
extracted into a `LedgerConfigurationPayloadProvider` interface so the gRPC handler
(`GetLedgerConfigurationHandler`) stays chain-agnostic and the ledger-specific work (block header
fetch, proofs, trust-anchor material) lives in the EVM module where the JSON-RPC clients are.

---

## Proto changes (`clpr-relay-proto`)

### `ClprLedgerConfigurationResponse` — **new**

File: `clpr_ledger_configuration_response.proto`

```protobuf
message ClprLedgerConfigurationResponse {
    oneof payload {
        QbftLedgerConfigurationPayload qbft = 1;
        // one additional field per new chain type, e.g.:
        // AcmeChainLedgerConfigurationPayload acme = 2;
    }
}
```

A wrapper returned by `getLedgerConfiguration`. Exactly one field is set at runtime; the others are
absent on the wire. The receiving peer inspects which field is non-null to determine the source
endpoint's consensus type and routes the payload to the appropriate verifier.

Each new chain type adds exactly one new nullable field to this message and a corresponding proto
file for its payload type. No existing fields are modified.

---

## Core module (`clpr-relay-core`)

### `LedgerConfigurationPayloadProvider`

```java
public interface LedgerConfigurationPayloadProvider {
    ClprLedgerConfigurationResponse provide(CommitmentLevel level);
}
```

The chain-agnostic wrapper `ClprLedgerConfigurationResponse` is the return type, so the interface is
independent of any specific consensus type; the caller inspects which field of the response is
non-null. Implementations pin all reads to the same block so the payload is internally consistent.

---

## App module (`clpr-relay-app`)

### `ProofType` — discriminator enum

```java
public enum ProofType { QBFT, CometBFT, Hiero }
```

Stored on each `LocalNetworkConfig` (the local chain's own consensus type) and used as the resolved
peer proof type per channel. It is switched on in three places:

1. `LocalNetworkAdapter.create()` — builds the CometBFT RPC client for a `CometBFT` network.
2. `ClprChannelHandler.create()` — selects the `BundleConstructor` and the
   `LedgerConfigurationPayloadProvider` for the local chain's proof type.
3. `ClprChannelHandler.codecFor()` — selects the inbound `BundlePayloadCodec` from the
   channel's resolved `peerProofType`.

`Hiero` is valid only as a resolved peer proof type (the EVM relay never submits to a Hiero chain
locally); `LocalNetworkConfig.proofType = Hiero` is rejected by the loader.

---

### `RelayConfig`

A structured list of named local networks, a list of `ClprService` deployments, and a top-level
`peerProofTypes` map.

**`LocalNetworkConfig` nested record:**

```java
public record LocalNetworkConfig(
    String id,
    ProofType proofType,
    CommonEvmParams evm,
    @Nullable CometBftConfig cometBft,
    @Nullable QbftConfig qbft) {}
```

The `id` is a user-chosen string referenced by `ClprServiceConfig.localNetwork`. The `proofType`
selects the implementation bundle and proof provider. The typed sub-blocks carry the parameters
specific to that consensus type — `qbft` is present for `QBFT`, `cometBft` for `CometBFT`; both run
an EVM JSON-RPC, so `evm` is always present.

**`ClprServiceConfig` nested record:**

```java
public record ClprServiceConfig(
    String defaultSigningPrivateKeyHex,
    String localNetwork,
    String serviceAddress,
    boolean discoverChannels,
    long discoveryStartBlock,
    List<String> predefinedChannels,
    Map<String, String> perChannelSigningPrivateKeyHex) {}
```

- `localNetwork` — the `id` of the `LocalNetworkConfig` this service is deployed on.
- `serviceAddress` — hex address of the deployed `ClprService` contract.
- `defaultSigningPrivateKeyHex` — signs every channel on this service without a per-channel
  override; the EVM sender address is derived from it. Required when the service discovers
  channels or has an unkeyed predefined channel.
- `discoverChannels` / `discoveryStartBlock` — enable and bound on-chain channel discovery.
- `predefinedChannels` — channel ids registered at startup.
- `perChannelSigningPrivateKeyHex` — per-channel signing-key overrides.

Channel-level knobs (commitment level, sync cadence, proof lag) are **not** per-channel
config: the service applies `LATEST`, `ChannelSyncTask.DEFAULT_INTERVAL_MS`, and a
`proofType`-derived proof lag uniformly to every channel it serves.

---

### `RelayConfigLoader`

The loader parses `localNetworks[]`, `clprServices[]`, and `peerProofTypes` from YAML and validates
cross-references at startup rather than at first use. Validation rules:

- Every `clprServices[].localNetwork` must reference a declared `localNetworks[]` entry.
- Each service must be able to sign for every channel it serves: `defaultSigningPrivateKeyHex`
  is required when the service discovers channels or has a predefined channel without a
  per-channel override.
- Each `localNetworks[]` entry must supply the `evm` block; `proofType: Hiero` is rejected.
- When `peerProofTypes` is null, missing, or empty, `RelayConfig.DEFAULT_PEER_PROOF_TYPES` is
  substituted.

File-config validation errors are wrapped as `IllegalStateException` naming the offending file.

---

### `RelayInstance`

The composition root builds the three-tier hierarchy and the relay-global pieces (the gRPC server,
the outbound `ClprEndpointClient`, and the metrics registry). Structure:

1. Build one `LocalNetworkAdapter` per `localNetworks[]` entry.
2. Build one `ClprServiceHandler` per `clprServices[]` entry, into a live map. Each service handler
   builds its `ClprChannelHandler`s lazily — predefined channels when the service starts,
   discovered channels at runtime — because construction performs blocking JSON-RPC reads
   (protocol-version check, ledger config, peer roster).
3. Build the four `ChannelMapped*` resolver adapters, plus the `ThrottleEnforcer` and
   `PeerEndpointCache` resolvers, over a `Function<Bytes, Optional<ClprChannelHandler>>` that
   iterates the service-handler map. Pass them to `ClprSyncHandler` / `ClprGrpcServer`.
4. Wire a `LedgerConfigurationPayloadProvider` to `GetLedgerConfigurationHandler` that serves the
   first registered channel's provider (resolved from the live topology at request time) as a
   temporary measure — `ClprGetLedgerConfigurationRequest` carries no `channel_id`. See
   "Remaining work".
5. Register the aggregate loop-failure gauges (`sync.channels.*`, `evm.listener.channels.*`),
   computed at scrape time from every live channel handler's `FailState`.

Per-channel component selection lives in `ClprChannelHandler.create()`, keyed off the local
network's proof type:

```java
switch (network.proofType()) {
    case QBFT -> {
        bundleConstructor = new QbftBundleConstructor(...);
        ledgerConfigProvider = new EvmQbftLedgerConfigurationProvider(...);
    }
    case CometBFT -> {
        bundleConstructor = new SeiBundleConstructor(...);
        ledgerConfigProvider = new SeiLedgerConfigurationProvider(...);
    }
    default -> throw new UnsupportedOperationException(...);
}
```

The inbound codec is selected by `ClprChannelHandler.codecFor(peerProofType, channelId)`.

---

### `ChannelMapped*` resolver adapters

All four live in `clpr-relay-app` (package-private) and hold the same
`Function<Bytes, Optional<ClprChannelHandler>>` resolver rather than their own maps. Resolution
happens at call time, so a channel registered after construction is served immediately.

- **`ChannelMappedTransactionSubmitter`** (implements `TransactionSubmitter`) — dispatches
  `submitBundle` to the resolved handler's `txSubmitter()`.
- **`ChannelMappedChannelLookup`** (implements `ChannelLookup`) — dispatches
  `readChannelState` to the resolved handler's `stateReader()`. `ClprSyncHandler` needs only
  channel lookup, so this adapter carries just that one method (issue #291); it returns
  `Optional.empty()` when no handler is registered for the id (mirroring the single-chain "no state
  for an unknown connection" behavior, letting `ClprSyncHandler` return an empty-proof response).
- **`ChannelMappedBundleConstructor`** (implements `BundleConstructor`) — dispatches
  `getLatestBundlePayload` / `onStateChanged` to the resolved handler's `bundleConstructor()`.
- **`ChannelMappedProofCodec`** (implements `BundlePayloadCodecResolver`) — a codec's methods
  carry no `channelId` (each codec is bound to one channel at construction), so this resolves
  the handler and returns its `inboundCodec()`. `ChannelSyncTask` holds its channel's codec
  directly; `ClprSyncHandler` holds the resolver and looks the codec up per request.

---

## EVM module (`clpr-relay-evm`)

### `EvmQbftLedgerConfigurationProvider` / `SeiLedgerConfigurationProvider`

`provide(CommitmentLevel)` returns a `ClprLedgerConfigurationResponse`, wrapping the
consensus-specific payload:

```java
return ClprLedgerConfigurationResponse.newBuilder().qbft(payload).build();
```

This aligns the providers with the `LedgerConfigurationPayloadProvider` interface and keeps
`GetLedgerConfigurationHandler` chain-agnostic.

### `EvmChannelStateChangeTask`

The per-channel on-chain state poller, driven directly by its `ClprChannelHandler` (there is
no central listener). It refreshes the channel's `PeerEndpointCache` from the on-ledger roster
when the peer-config marker advances, and notifies the `BundleConstructor` when the queue state
advances.

---

## gRPC module (`clpr-relay-grpc-server`)

### `GetLedgerConfigurationHandler`

```java
public final class GetLedgerConfigurationHandler {
    public GetLedgerConfigurationHandler(
        LedgerConfigurationPayloadProvider provider,
        CommitmentLevel commitmentLevel) { ... }

    public ClprLedgerConfigurationResponse handle(
        ClprGetLedgerConfigurationRequest request) { ... }
}
```

Extracting the handler from `ClprEndpointServiceImpl` makes `getLedgerConfiguration` independently
testable and gives the `LedgerConfigurationPayloadProvider` a clean injection point.

### `ClprSyncHandler` / `ClprGrpcServer` / `ClprEndpointServiceImpl`

The channel-scoped lookups these classes perform are now `Function<Bytes, …>` resolvers rather
than `Map<Bytes, …>`: `ClprSyncHandler` takes a `Function<Bytes, ThrottleEnforcer>`, and
`ClprGrpcServer`/`ClprEndpointServiceImpl` take a `Function<Bytes, PeerEndpointCache>` (both return
`null` for an unknown connection id). `RelayInstance` backs both with the connection-handler resolver.

---

## Adding a new chain type — extension checklist

The following steps are the complete surface area that a new chain type touches.

### 1. Proto — new payload message

Create `clpr_<name>_ledger_configuration_payload.proto` defining the consensus-specific
trust-anchor fields alongside any shared types. Add a field for the new message to
`ClprLedgerConfigurationResponse`:

```protobuf
message ClprLedgerConfigurationResponse {
    oneof payload {
        QbftLedgerConfigurationPayload qbft  = 1;
        AcmeChainPayload               acme  = 2;  // new field
    }
}
```

Regenerate PBJ: `./gradlew :clpr-relay-proto:generatePbjSource`.

### 2. App — extend `ProofType`

Add one value to the enum (`ProofType { QBFT, CometBFT, Hiero, ACME }`). The compiler surfaces every
exhaustive `switch` that must be extended — the proof-type switch in `ClprChannelHandler.create()`
and the codec switch in `ClprChannelHandler.codecFor()`.

### 3. App — add chain-specific params record

Define a params record for the new chain type and add a nullable field for it to
`LocalNetworkConfig`. Add parsing in `RelayConfigLoader.parseLocalNetwork()` under the new case, plus
a startup validation guard that the block is present when its `proofType` is selected. Validate
required fields explicitly (name the network `id` and the missing field); apply defaults only for
optional fields. Document the new block in `relay-config-example.yaml` and `docs/configuration.md`,
noting which `ProofType` requires it.

### 4. EVM — implement `LedgerConfigurationPayloadProvider` (and, if needed, a codec/constructor)

Create the provider in `clpr-relay-evm`. It must pin all reads to the same block and wrap its payload
in `ClprLedgerConfigurationResponse`. If the chain shares the EIP-1186 bundle format,
`QbftBundleConstructor` / `QbftProofCodec` can be reused; otherwise add a new
`BundleConstructor` / `BundlePayloadCodec` pair. Both should be independently unit-testable via a
stub `EvmJsonRpcClient`.

### 5. App — extend the `ClprChannelHandler` switches

Add the new `case` to the proof-type switch in `ClprChannelHandler.create()` (selecting the
`BundleConstructor` + `LedgerConfigurationPayloadProvider`) and to `ClprChannelHandler.codecFor()`
(selecting the inbound `BundlePayloadCodec`). If the new chain needs a different transport client,
extend `LocalNetworkAdapter.create()` to build it (and expose it via an accessor).

### 6. Tests

- Unit test the new `LedgerConfigurationPayloadProvider` with a stub JSON-RPC client.
- If a new `BundlePayloadCodec` is added, unit test it against a known-good bundle fixture (see
  `QbftProofCodecTest` as the reference pattern).
- Extend `RelayConfigLoaderTest` to cover the new chain-specific param validation (present, absent,
  wrong chain type).

---

## Remaining work

### `getLedgerConfiguration` routing for multiple local chain targets

The channel-scoped wiring (per-channel submitters, readers, constructors, codecs, resolved via
the `ClprChannelHandler` lookup) already handles multiple local networks and services correctly.
The gap is in `getLedgerConfiguration`.

`ClprGetLedgerConfigurationRequest` is currently an empty message — the caller provides no routing
information. `GetLedgerConfigurationHandler` therefore holds a single
`LedgerConfigurationPayloadProvider` and can only serve one chain's configuration (today
`RelayInstance` wires it to the first registered channel's provider). When a relay manages
channels to two different local chains, it cannot tell which chain's config a calling peer needs.

**Required change:** add a routing field to `ClprGetLedgerConfigurationRequest`:

```protobuf
message ClprGetLedgerConfigurationRequest {
    // The channel for which the caller wants ledger configuration.
    // The server returns the config for that channel's local chain target.
    bytes channel_id = 1;
}
```

With this field, `GetLedgerConfigurationHandler` can resolve the provider per request through the
same channel-handler lookup the other gRPC paths use.

**Note:** this is a wire protocol change. It must be coordinated with the Hiero-native CLPR endpoint
since both sides implement the same `getLedgerConfiguration` RPC.
