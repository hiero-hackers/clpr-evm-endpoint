# Local development environment

Two setups are available: **Docker Compose** (lighter, no Kubernetes) and **kind**
(full Kubernetes cluster). Both use the same Anvil node, the same
`dev/scripts/deploy-contracts.sh` deploy logic, `dev/scripts/send-messages.sh` for
continuous message traffic, and `dev/dashboards/clpr-relay.json` for the Grafana
dashboard.

## Docker Compose (recommended for relay behaviour testing)

The only prerequisites are **Docker** and **Docker Compose**. Forge, cast, and Anvil
all run inside the Foundry container — nothing else needed locally.

```bash
# clpr-smart-contracts must be built first (forge is in the container, but
# the build artefacts need to exist on disk for the deployer to read).
# If the repo is a sibling:
cd ../clpr-smart-contracts && docker run --rm -v "$PWD":/work -w /work \
    ghcr.io/foundry-rs/foundry:latest forge build && cd -

# Start core services from the dev/ directory (Anvil, deployer, relay-a, relay-b,
# message sender):
cd dev && docker compose up --build

# To also start Prometheus and Grafana:
cd dev && docker compose --profile monitoring up --build
```

Optional environment variables:

|      Variable      |           Default            |                        Description                         |
|--------------------|------------------------------|------------------------------------------------------------|
| `CONTRACTS_DIR`    | `../../clpr-smart-contracts` | Path to the `clpr-smart-contracts` repo                    |
| `MSG_INTERVAL_MS`  | `5000`                       | Message sender interval in milliseconds                    |
| `SECURE_TRANSPORT` | `1`                          | mTLS on the sync plane (see below); `0` for plaintext gRPC |

```bash
cd dev && CONTRACTS_DIR=/path/to/clpr-smart-contracts MSG_INTERVAL_MS=1000 docker compose up --build
```

|               URL                |  Credentials  | Requires profile |
|----------------------------------|---------------|------------------|
| Anvil RPC http://localhost:8545  | —             | (always on)      |
| Grafana http://localhost:3000    | admin / admin | `monitoring`     |
| Prometheus http://localhost:9090 | —             | `monitoring`     |

When started with `--profile monitoring`, the relay dashboard appears in Grafana. Relay-a
and relay-b metrics are both scraped by Prometheus; select by `instance` label in
dashboard panels.

To tear down and remove the generated config volume:

```bash
docker compose down -v
```

> **Apple Silicon note:** `ghcr.io/foundry-rs/foundry:latest` is `linux/amd64` only
> and runs under Rosetta via Docker Desktop — acceptable for dev use.

### mTLS (secure sync plane)

The Compose stack can run the two relays with mandatory mTLS on the **sync** (data) plane
instead of plaintext. The **info** plane is always plaintext (it serves chain-verifiable
public data). This is controlled by the `SECURE_TRANSPORT` env var, which **defaults to `1`**
— so `docker compose up` is already sync-mTLS unless you set `SECURE_TRANSPORT=0`.

This uses the relay's **two-tier** mTLS model: the endpoint's durable root of trust is an
**ECDSA P-384 CA** whose certificate is published on-chain as `ClprEndpoint.tls_certificate`.
The relay loads only the **CA private key** from disk (`grpc.sync.tlsKeyPath`) — never the
cert; at startup it mints a fresh **Ed25519 leaf** signed by that key (issuer and subject the
fixed `CN=x`, matching the on-chain CA's subject) and presents the *leaf* on the wire. A peer
trusts it by PKIX path validation of the leaf against the CA it holds from the roster (no
fingerprint pinning — the CA is the trust anchor).

How it fits together:

- `dev/scripts/gen-certs.sh` mints one self-signed **ECDSA P-384 CA** cert/key per relay
  into `dev/certs/` — `<relay>-cert.der` (X.509 DER), `<relay>-key.der` (PKCS#8 DER), and
  `<relay>-cert.hex` (the DER as hex). The CA key must be EC P-384: the leaf is signed with
  `SHA384withECDSA`, so an RSA CA fails leaf generation at startup.
- `deploy-contracts.sh` gets each **peer's** CA cert into the roster so it can be pinned.
  It injects the cert **only through the mock verifier's `setSeedEndpoints`** (chain A
  carries relay-B's CA, chain B carries relay-A's); `_populatePeerEndpointRoster` copies it
  into the roster at `completeChannel`, and the relay reads it via `getPeerEndpointRoster`.
  It deliberately leaves the cert **out** of the on-chain `updateLedgerConfiguration` seed
  endpoints: that path runs `EndpointValidation.validateTlsCertificate`, which still requires
  a **DER RSA ≥2048** cert and rejects the relay's EC cert — a drift between
  `clpr-smart-contracts` and the relay's two-tier model. (The ledger seeds keep an empty
  cert, which the validator accepts.)
- `dev/certs/` is mounted read-only into the deployer and both relay containers; the
  generated `relay-a.yaml`/`relay-b.yaml` set `grpc.sync.port: 9545` with
  `grpc.sync.tlsEnabled: true` + `grpc.sync.tlsKeyPath` (mTLS; the CA cert is published on-chain,
  not read by the relay) and a plaintext `grpc.info.port: 9546`.

Prerequisites for this mode: `openssl` and `xxd` on the host (macOS ships both). Use a
modern `openssl` (e.g. Homebrew's) — the system LibreSSL may not negotiate the Ed25519
leaf over TLS 1.3, which would make the probes below fail for the wrong reason.

Recipe — bring it up and prove mTLS is working (run from `dev/`):

```bash
# 1. Generate the per-relay EC P-384 CAs (writes dev/certs/).
./scripts/gen-certs.sh

# 2. Bring up the stack (SECURE_TRANSPORT defaults to 1). Add --profile monitoring for Grafana.
docker compose up -d --build

# 3. Wait for the contract deployer to finish (exit 0), then confirm the sync listener is mTLS.
docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' clpr-relay-dev-deployer-1
docker compose logs relay-a | grep 'gRPC server started'
#   expect: sync: 9545 [mTLS]; info: 9546 [plaintext]

# 4a. Bidirectional sync over mTLS is flowing (sync is mTLS-only => every success is a full
#     mutual-TLS handshake). Run twice a few seconds apart; the counts climb.
curl -s http://localhost:9547/metrics | grep clpr_sync_outbound_peer_success   # relay-a -> 172.30.0.12:9545
curl -s http://localhost:9557/metrics | grep clpr_sync_outbound_peer_success   # relay-b -> 172.30.0.11:9545
docker compose logs relay-a | grep -iE 'SSLException|CertificateException|leaf' || echo "no TLS errors"

# 4b. On the mTLS sync port the relay presents an Ed25519 LEAF issued by its CA. openssl (no client
#     cert) still captures the server leaf before the handshake fails on the cert request; verify the
#     leaf chains to the on-disk CA (which is the cert published on-chain).
echo | openssl s_client -connect localhost:9545 2>/dev/null | openssl x509 -noout -subject -issuer
#   expect: subject=CN=x   issuer=CN=x   (leaf issuer = the CA subject, both the fixed CN=x)
openssl x509 -inform DER -in certs/relay-a-cert.der -out /tmp/ca-a.pem 2>/dev/null
echo | openssl s_client -connect localhost:9545 2>/dev/null | openssl x509 -out /tmp/leaf-a.pem 2>/dev/null
openssl verify -CAfile /tmp/ca-a.pem -partial_chain /tmp/leaf-a.pem      # expect: /tmp/leaf-a.pem: OK

# 4c. Negative test — the sync port REQUIRES a client cert. TLS 1.3 sends the alert after the
#     handshake, so let openssl read it. (Port 9546 is plaintext — not a TLS endpoint at all.)
(sleep 1.5; printf 'GET / HTTP/1.1\r\n\r\n'; sleep 0.5) | openssl s_client -connect localhost:9545 2>&1 | grep -i 'alert'
```

Expected in 4c: `:9545` → `tlsv13 alert certificate required ... alert number 116`. (`openssl`'s
`Verify ... unable to verify the first certificate` note is expected — the leaf is issued by a private
CA the host doesn't trust; the relay validates via the on-chain CA, not the host trust store.)

To A/B against plaintext, start with `SECURE_TRANSPORT=0 docker compose up -d --build`
(no certs needed); the same `clpr_sync_outbound_peer_success` counters climb over plaintext.

> **Note:** `dev/certs/` holds private keys — it is dev-only throwaway material.
> Regenerate anytime with `./scripts/gen-certs.sh`; do not commit it.

## kind (full Kubernetes cluster)

Required locally: `kind`, `kubectl`, `helm`, `docker`. Forge, cast, and Anvil run inside the Foundry container — nothing else needed locally.

```bash
# Prerequisites (macOS)
brew install kind helm kubectl

# Build smart contract artifacts (forge runs in the container):
cd ../clpr-smart-contracts && docker run --rm -v "$PWD":/work -w /work \
    ghcr.io/foundry-rs/foundry:latest forge build && cd -

CONTRACTS_DIR=/path/to/clpr-smart-contracts MSG_INTERVAL_MS=1000 ./dev/kind-setup.sh
```

Optional environment variables:

|     Variable      |          Default          |                     Description                     |
|-------------------|---------------------------|-----------------------------------------------------|
| `CONTRACTS_DIR`   | `../clpr-smart-contracts` | Path to the `clpr-smart-contracts` repo             |
| `MSG_INTERVAL_MS` | `5000`                    | Message sender interval in milliseconds             |
| `MONITORING`      | `true`                    | Install kube-prometheus-stack and Grafana dashboard |
| `KIND_CLUSTER`    | `clpr-dev`                | kind cluster name                                   |
| `NAMESPACE`       | `default`                 | Kubernetes namespace                                |

Everything runs inside the kind cluster — no external Docker containers or port-forwards
are needed after the cluster is up.

The script:
1. Builds the relay Docker image
2. Generates a kind cluster config (with `extraMounts` for the contracts dir and `/tmp`)
and creates the `clpr-dev` cluster
3. Loads the relay image into kind
4. Installs **kube-prometheus-stack** (shared Grafana + Prometheus)
5. Deploys **Anvil** as a Deployment + ClusterIP Service inside the cluster
6. Runs `dev/scripts/deploy-contracts.sh` as a Kubernetes **Job** (contracts mounted via
`extraMounts`, output env file written directly to the host via the `/tmp` mount)
7. Deploys the Grafana dashboard ConfigMap, then deploys **relay-a** and **relay-b**
via Helm with `evm.jsonRpcUrl=http://anvil:8545` and `ServiceMonitor` enabled
8. Verifies both relay HTTP servers are reachable (port-forward + `/metrics` poll),
then applies the **msg-sender** as a Deployment and tails both relay logs

|                URL                |  Credentials  |
|-----------------------------------|---------------|
| Grafana http://localhost:30300    | admin / admin |
| Prometheus http://localhost:30090 | —             |

The **CLPR EVM Relay** dashboard appears in Grafana. Both
relay-a and relay-b metrics are scraped by the same Prometheus so the dashboard shows
both channels simultaneously.

## Tear down

```bash
# Docker Compose (run from the dev/ directory)
docker compose down -v    # -v removes the generated config volume

# kind
./dev/kind-teardown.sh
```

## Files

### Shared (used by both setups)

|             File              |                                                  Purpose                                                   |
|-------------------------------|------------------------------------------------------------------------------------------------------------|
| `scripts/deploy-contracts.sh` | Deploys two ClprService contract sets to Anvil, configures and wires them up, writes a sourceable env file |
| `scripts/send-messages.sh`    | Continuously sends one test message per chain every 5 s using Anvil account 2                              |
| `dashboards/clpr-relay.json`  | Grafana dashboard JSON — bind-mounted in Compose, deployed as a ConfigMap in kind                          |

### Docker Compose

|                            File                            |                                                     Purpose                                                     |
|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `compose/entrypoint-deployer.sh`                           | Deployer container entrypoint: calls `deploy-contracts.sh`, then writes relay YAML configs to the shared volume |
| `compose/prometheus.yml`                                   | Prometheus static scrape config targeting `relay-a:9547` and `relay-b:9547`                                     |
| `compose/grafana/provisioning/datasources/prometheus.yaml` | Grafana datasource provisioning pointing at the `prometheus` container                                          |
| `compose/grafana/provisioning/dashboards/dashboards.yaml`  | Grafana dashboard provider loading from `/var/lib/grafana/dashboards/`                                          |

### kind

|                File                 |                                                              Purpose                                                              |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `kind-setup.sh`                     | End-to-end kind setup script; generates the kind cluster config dynamically so `CONTRACTS_DIR` can be injected as an `extraMount` |
| `kind-teardown.sh`                  | Deletes the kind cluster (`kind delete cluster`) — removes all workloads in one step                                              |
| `values/kube-prometheus-stack.yaml` | kps values: sidecar dashboards enabled, NodePort services, minimal resource requests                                              |
| `values/relay.yaml`                 | Relay chart dev values (reference — `kind-setup.sh` passes flags directly)                                                        |

## How contract setup works

`deploy-contracts.sh` uses Foundry's `forge` and `cast` tools directly against the
local Anvil node

1. Derives the 64-byte secp256k1 public keys for relay A (Anvil account 0) and relay B (Anvil account 1)
2. Deploys two independent contract sets from `../clpr-smart-contracts`:
   - 6 logic / library contracts (`ChannelLogic`, `MessagingLogic`, `BundleLogic`, etc.)
   - `ClprService`
   - `QbftPassThroughVerifier`, `MockClprApplication`, `MockClprConnector`
3. Configures economics and ledger on both chains (seed endpoints point to the peer relay's K8s hostname and signing key)
4. Registers each relay as an endpoint with a 0.01 ETH bond
5. Configures each `QbftPassThroughVerifier` with the peer chain's service address and throttles
6. Registers a channel and a connector on both chains using the same commit-reveal + EIP-191 signing that the relay itself uses
7. Sends an initial message in each direction
8. Writes `/tmp/clpr-kind-setup.env` via the `/tmp` `extraMount` so `kind-setup.sh`
   can source it on the host immediately after the Job completes

The env file contains `CONTRACT_A`, `CONTRACT_B`, `CHANNEL_ID`, `CONNECTOR_ID`,
`MOCK_APP_A/B`, and the Anvil signing keys/addresses used by the Helm deployments.

## Anvil dev accounts

| Account |                   Address                    |                                 Role                                 |
|---------|----------------------------------------------|----------------------------------------------------------------------|
| 0       | `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` | Relay A signer / contract deployer                                   |
| 1       | `0x70997970C51812dc3A010C7d01b50e0d17dc79C8` | Relay B signer                                                       |
| 2       | `0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC` | Dedicated message sender (avoids nonce conflicts with relay signers) |
