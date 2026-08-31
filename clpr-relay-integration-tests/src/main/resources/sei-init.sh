#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
#
# Initialises and starts a single-container Sei devnet for integration testing.
#
# WHY A CLUSTER (not one node): Sei v6.5.2's block-sync reactor is unconditional
# (there is no config key or start flag to disable it) and only hands off to
# consensus once BlockPool.IsCaughtUp() is true — which requires the node to have
# at least two P2P peers (see sei-tendermint internal/blocksync/pool.go:
# `if len(pool.peers) <= 1 { return false }`). A lone node has zero peers, so it
# stays wedged in block-sync at height 0 forever and never opens the EVM RPC.
# We therefore run three seid processes in one container — one validator that
# produces and signs every block (single-validator genesis ⇒ simple light-client
# proofs) plus two non-validator full nodes whose only job is to be peers so the
# validator clears the >=2-peer gate. All three share one genesis.
#
# Only the validator's endpoints are exposed by SeiContainer: EVM JSON-RPC on
# 8545 and CometBFT RPC on 26657. The two helper full nodes use offset ports and
# log to /tmp so they don't pollute `docker logs` (which shows the validator).

set -euo pipefail

CHAIN_ID="${SEI_CHAIN_ID:-sei-local}"
KEYNAME="validator"

# Node homes. H0 is the exposed validator; H1/H2 are internal full-node peers.
H0="${SEI_HOME:-/root/.sei}"
H1="/root/.sei-peer1"
H2="/root/.sei-peer2"

# Per-node ports. The validator keeps the conventional ports (exposed by the
# container); the peers are offset to avoid in-container collisions.
P2P0=26656; RPC0=26657
P2P1=36656; RPC1=36657
P2P2=46656; RPC2=46657

echo "[sei-init] chain-id=${CHAIN_ID} (1 validator + 2 full-node peers in one container)"

# ── 1. Initialise all three node homes ────────────────────────────────────────
seid init test-node       --chain-id "${CHAIN_ID}" --home "${H0}" --overwrite >/dev/null 2>&1
seid init test-node-peer1 --chain-id "${CHAIN_ID}" --home "${H1}" --overwrite >/dev/null 2>&1
seid init test-node-peer2 --chain-id "${CHAIN_ID}" --home "${H2}" --overwrite >/dev/null 2>&1

# ── 2. Import the well-known test key (Foundry/Anvil account 0) into validator ─
# mnemonic → private key 0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
# EVM address 0xf39Fd6e51aad88F6f4ce6aB8827279cffFb92266
# --coin-type 60 selects the Ethereum HD path m/44'/60'/0'/0/0.
# eth_secp256k1 was removed in v6; secp256k1 with coin-type 60 derives the same key.
echo "test test test test test test test test test test test junk" | \
    seid keys add "${KEYNAME}" \
        --keyring-backend test \
        --recover \
        --coin-type 60 \
        --home "${H0}" >/dev/null 2>&1

# ── 3. Build the shared genesis on the validator home ─────────────────────────
# Single validator: only H0 contributes a gentx. The two full nodes are not
# validators (they just follow consensus and act as peers).
seid add-genesis-account "${KEYNAME}" \
    100000000000000000000000000usei \
    --keyring-backend test \
    --home "${H0}" >/dev/null 2>&1

seid gentx "${KEYNAME}" 1000000000000usei \
    --chain-id "${CHAIN_ID}" \
    --keyring-backend test \
    --home "${H0}" >/dev/null 2>&1

seid collect-gentxs --home "${H0}" >/dev/null 2>&1

# Distribute the finished genesis to the full nodes so all three agree on it.
cp "${H0}/config/genesis.json" "${H1}/config/genesis.json"
cp "${H0}/config/genesis.json" "${H2}/config/genesis.json"

# ── 4. Discover node IDs and wire the peer mesh ───────────────────────────────
ID0=$(seid tendermint show-node-id --home "${H0}")
ID1=$(seid tendermint show-node-id --home "${H1}")
ID2=$(seid tendermint show-node-id --home "${H2}")
echo "[sei-init] node ids: validator=${ID0} peer1=${ID1} peer2=${ID2}"

# Per-node config. Everything that has a `seid start` flag is passed at start
# time (below); only what has no flag is edited here:
#   * mode="validator"      — config.toml only; the --mode start flag loads the
#                             priv-validator key but does not make block-sync
#                             hand off, so the validating node needs it in-file.
#   * allow-duplicate-ip    — all three peers share 127.0.0.1, so P2P must allow
#                             duplicate IPs or the mesh never forms.
#   * EVM HTTP/WS (app.toml) — no start flag exists. Enable only on the exposed
#                             validator (8545/8546); disable on the peers so they
#                             don't fight over those ports.
configure_node() {
    local home="$1" is_validator="$2"
    local cfg="${home}/config/config.toml" app="${home}/config/app.toml"

    # All three peers share 127.0.0.1, so P2P must allow duplicate IPs.
    sed -i 's/^allow-duplicate-ip = .*/allow-duplicate-ip = true/' "${cfg}"

    if [ "${is_validator}" = "yes" ]; then
        # Validating node: produces and signs every block, and serves the EVM
        # JSON-RPC on the conventional 8545 (the only EVM endpoint the container
        # exposes). The cosmos API (1317) / gRPC (9090) / gRPC-web (9091) servers
        # stay ENABLED here — Sei's EVM JSON-RPC depends on them — and there is no
        # port clash because the peer nodes disable those servers (below).
        sed -i 's/^mode = "full"/mode = "validator"/' "${cfg}"
        sed -i 's/^http_enabled = .*/http_enabled = true/' "${app}"
        sed -i 's/^http_port = .*/http_port = 8545/' "${app}"
        # Pace block production. Defaults (all unsafe-* overrides "0s",
        # create-empty-blocks-interval "0s") make the lone validator mint empty
        # blocks as fast as it can (tens per second), churning disk for the whole
        # test. A 1s commit timeout yields ~1 block/s — ample for the test, which
        # only needs the chain to keep advancing.
        sed -i 's/^unsafe-commit-timeout-override = .*/unsafe-commit-timeout-override = "1s"/' "${cfg}"
        # Lift SeiDB's historical-proof rate limit. It defaults to 1 req/s, burst 1,
        # max 1 in-flight, so a consumer that fetches several ICS-23 proofs at the
        # same (non-latest) height in quick succession — exactly what a CLPR bundle
        # does, one proof per storage slot — gets all but the first rejected with
        # "historical proof rate limited" (ABCI code 36). Disable the limit and
        # widen concurrency for the test node (a rate limit <=0 disables it).
        sed -i 's/^sc-historical-proof-rate-limit = .*/sc-historical-proof-rate-limit = 0/' "${app}"
        sed -i 's/^sc-historical-proof-burst = .*/sc-historical-proof-burst = 100/' "${app}"
        sed -i 's/^sc-historical-proof-max-inflight = .*/sc-historical-proof-max-inflight = 100/' "${app}"
    else
        # Peer node: consensus + P2P + its own (offset) CometBFT RPC only. Disable
        # the EVM RPC and the cosmos API / gRPC / gRPC-web servers so they don't
        # collide with the validator's, which uses the default ports. `[api]`,
        # `[grpc]` and `[grpc-web]` are the only `^enable = true` lines in app.toml
        # (telemetry uses `enabled`, EVM uses `http_enabled`).
        sed -i 's/^http_enabled = .*/http_enabled = false/' "${app}"
        sed -i 's/^ws_enabled = .*/ws_enabled = false/' "${app}"
        sed -i 's/^enable = true$/enable = false/' "${app}"
    fi
}
configure_node "${H0}" yes
configure_node "${H1}" no
configure_node "${H2}" no

# ── 5. Start the two full-node peers in the background ────────────────────────
start_peer() {
    local home="$1" p2p="$2" rpc="$3" peers="$4" logf="$5"
    seid start \
        --home "${home}" \
        --mode full \
        --p2p.laddr="tcp://0.0.0.0:${p2p}" \
        --rpc.laddr="tcp://0.0.0.0:${rpc}" \
        --p2p.persistent-peers="${peers}" \
        >"${logf}" 2>&1 &
}
start_peer "${H1}" "${P2P1}" "${RPC1}" "${ID0}@127.0.0.1:${P2P0},${ID2}@127.0.0.1:${P2P2}" /tmp/sei-peer1.log
start_peer "${H2}" "${P2P2}" "${RPC2}" "${ID0}@127.0.0.1:${P2P0},${ID1}@127.0.0.1:${P2P1}" /tmp/sei-peer2.log

# Give the peers a moment to bind their P2P listeners before the validator dials.
sleep 3

# ── 6. Start the validator in the foreground (PID 1 of the container) ──────────
exec seid start \
    --home "${H0}" \
    --p2p.laddr="tcp://0.0.0.0:${P2P0}" \
    --rpc.laddr="tcp://0.0.0.0:${RPC0}" \
    --p2p.persistent-peers="${ID1}@127.0.0.1:${P2P1},${ID2}@127.0.0.1:${P2P2}"
