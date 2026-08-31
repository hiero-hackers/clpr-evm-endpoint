# CLAUDE.md

Guidance for Claude Code working in this repository. This file covers project intent, conventions, and procedural guidance that isn't captured in the agent reference docs imported below or in the user-facing `docs/` tree.

@.claude/instructions.md
@.claude/build-commands.md
@.claude/module-structure.md
@.claude/conventions.md
@.claude/git-hooks.md
@docs/README.md

## What this project is

A standalone Java 25 process implementing a **CLPR endpoint** for an EVM-compatible chain. It reads on-chain state from a deployed `ClprService` Solidity contract (via JSON-RPC `eth_call`), submits `submitBundle(bytes32,bytes)` transactions on behalf of the local ledger, and exchanges sync payloads with peer endpoints over gRPC.

This is the EVM-side counterpart to a Hiero-native CLPR endpoint. Both implement the same wire protocol; this project does **not** implement the Hiero side — see the `clpr-hiero` companion repo.

Before deep work, read `docs/README.md` (the documentation index) and load the topic-specific doc(s) it lists for the task at hand.

## Local environment vs production defaults

Several config values are convenient for dev but unsafe in production:

- `clprServices[].defaultSigningPrivateKeyHex` has no default (empty, validated at startup), but the shipped `relay-config-example.yaml` uses the Anvil dev-account key — replace it in production. All EVM transactions are client-signed EIP-1559 raw txs from this key (no `eth_sendTransaction` fallback); the EVM sender address is derived from it.
- `clprServices[].serviceAddress` is the zero address in the example — a non-functional placeholder. The relay will refuse to do anything useful without a real deployed `ClprService` address.
- `localNetworks[].evm.maxGasPriceCap` defaults to `Long.MAX_VALUE` — effectively unlimited. Set a real ceiling in production.

See `docs/configuration.md` for the full production checklist. Don't assume defaults reflect production policy.

## When adding code

- **New config field**: add to `clpr-relay-app/.../RelayConfig.java` (or the nested record for its section) with a sensible default in code. Wire YAML loading and `-Drelay.<key>` system-property override using the existing helpers. Document the field inline in `clpr-relay-app/src/main/resources/relay-config-example.yaml`. Update `docs/configuration.md`'s schema table in the same change. Validate at startup so misconfiguration fails before the relay accepts traffic.
- **New core abstraction**: define the interface in `clpr-relay-core` (no I/O). Concrete EVM implementations go in `clpr-relay-evm`; gRPC server implementations in `clpr-relay-grpc-server`; outbound peer client in `clpr-relay-grpc-client`; orchestration in `clpr-relay-sync`. Wire the chosen implementation in `clpr-relay-app/.../RelayInstance.java`. Add the interface to the "Key abstractions" table in `docs/architecture.md`.
- **New EVM contract method**: extend `EvmContractStateReader` (for reads) or `AccountTransactionSubmitter` (for writes). ABI encoding goes through `AbiCodec`; do not hand-roll ABI byte arrays. Add a unit test that exercises the codec output against a known good fixture.
- **New gRPC RPC**: add it to the `.proto` in `clpr-relay-proto`, regenerate PBJ (`./gradlew :clpr-relay-proto:generatePbjSource`), then implement the server side in `ClprEndpointServiceImpl` (in `clpr-relay-grpc-server`) and the outbound side in `ClprEndpointClient` (in `clpr-relay-grpc-client`). Update `docs/sync-protocol.md`.
- **New channel-level behaviour** (rate limit, retry, backoff): prefer a decorator around an existing `core` interface — that is the project's idiom (see the `submitterDecorator` `UnaryOperator<TransactionSubmitter>` applied in `ClprChannelHandler` around the per-account submitter's `forContract(serviceAddress)` view — e.g. the integration suite's `StubReencodingTransactionSubmitter`; production wires `UnaryOperator.identity()`). Wire channel-scoped decorators in `ClprChannelHandler` (where the per-channel submitter is built), between the underlying implementation and its consumers; relay-global decorators go in `RelayInstance`.
- **New metric**: register through the `Metrics` handle passed to `RelayInstance.build`. Names use dot.notation; add the metric to the list in `docs/configuration.md`.
- **New Helm chart change**: edit files under `charts/templates/`. Run `helm lint charts/` and `helm template demo charts/` after every change. Optional resources (ServiceMonitor / PodLogs / NetworkPolicy / PDB / HPA) MUST be gated by an `.enabled` value defaulting to `false` and (for CRD-dependent resources) wrapped in `{{- if .Capabilities.APIVersions.Has "<group>/<version>" -}}` so the chart installs cleanly on clusters without the CRD.
- **New CI step**: edit the relevant workflow under `.github/workflows/` (the build pipeline lives in `200-flow-pr-checks.yaml`). Every third-party `uses:` reference must be a full 40-character SHA with a trailing `# v<version>` comment — `actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4`, never `@v4` or `@main`. See `.claude/conventions.md` for the rule.
- **New CI workflow file**: file under `.github/workflows/` following the **required** conventions in `.github/workflows/docs/naming-standards.md` — file name `ddd-xxxx-<name>.yaml`, workflow `name:` `ddd: [XXXX] <Name>`. Pick the prefix from the table (000 user-centric, 100 operational, 200 PR/CITR, 300 main-branch trigger, 700 AI helper, 800 reusable, 900 cron). Match the workflow code (`user` / `flow` / `call` / `cron` / `disp`) to the trigger. To exercise a `workflow_dispatch` workflow from a feature branch before it lands on `main`, follow the syntax-error registration trick documented in `.claude/workflow-dispatch-testing.md`.
- **Release behaviour change**: edit `.releaserc.json` for semantic-release plugin config (commit-analyzer rules, release notes preset, exec hooks, branch channels). The `prepareCmd` and `publishCmd` route to `scripts/release-prepare.sh` and `scripts/release-publish.sh`; keep version stamping logic in `release-prepare.sh` and registry-push logic in `release-publish.sh`. The release workflow (`800-call-semantic-release.yaml`) authenticates to JFrog Artifactory via GitHub OIDC and feeds `JFROG_REGISTRY` / `JFROG_DOCKER_REPO` / `JFROG_HELM_REPO` env to the publish script. When adding a new OCI artifact (additional image, additional chart), extend `release-publish.sh` and add the corresponding repo input to the workflow.

  The release also needs three repo secrets, configured under **Settings → Secrets and variables → Actions**:
  - `GH_ACCESS_TOKEN` — a PAT (or GitHub App installation token) with `contents: write` AND on the **bypass list** of the ruleset that protects `main`. Required because `@semantic-release/git` pushes the `chore(release): X.Y.Z` commit and the version tag directly to `main`; the default `GITHUB_TOKEN` is rejected by the `pull_request` rule (`GH013`).
  - `GPG_KEY_CONTENTS` — ASCII-armored GPG private key (`gpg --armor --export-secret-keys <fingerprint>`) used to sign the release commit so the `required_signatures` rule on `main` accepts it. The key's UID email becomes the committer email of the release commit.
  - `GPG_KEY_PASSPHRASE` — passphrase for `GPG_KEY_CONTENTS`. Use an empty string if the key has no passphrase, never omit the secret.

- **New JPMS-patched transitive**: edit the `module(...)` block in `settings.gradle.kts`. Don't reach for `module-info` patches in individual subproject build files for transitive deps — the central list keeps the patches consistent across modules.

- **New dependency version bump**: edit `hiero-dependency-versions/build.gradle.kts` only. Don't pin versions in individual `clpr-relay-*/build.gradle.kts` files.

## Spec / on-chain coupling

The relay's correctness is anchored in two external sources of truth:

1. The CLPR protocol spec (`clpr-spec/clpr-service-spec.md`, in a separate repo) — wire formats, on-ledger state model, security model.
2. The deployed `ClprService` contract bytecode (in `clpr-smart-contracts`, also a separate repo) — the actual ABI and storage layout.

When making changes that touch wire payloads, signatures, ABI calls, or contract method selectors, cross-reference the spec section and the latest contract sources. Drift between this repo and the contract surface is tracked in `docs/DRIFT-REVIEW-*.md`.

`RelayProtocol.PROTOCOL_VERSION` (currently `1`) is checked against the contract's `protocolVersion()` when each channel is registered (a mismatch skips that channel). Bumping it is a deliberate, coordinated change with the contract team.

## Things to leave alone unless asked

- The PBJ-generated output under `clpr-relay-proto/build/generated/...` — regenerate via Gradle, never hand-edit.
- The JPMS patches in `settings.gradle.kts` — extend, don't remove. Each entry exists because removing it broke `ExtraJavaModuleInfoTransform` at some point.
- `clpr-relay-integration-tests/` Docker-bound fixtures — leave the Testcontainers configuration alone unless the task is integration-test focused.
- `version.txt` and `gradle.properties` — these are touched by release tooling, not in normal feature work.
- The `evm/EthSigner` and `grpc-server/EndpointSigner` signing paths are security-sensitive. The per-channel `EthSigner` is built by `ClprServiceHandler` from `clprServices[].defaultSigningPrivateKeyHex` (or a `perChannelSigningPrivateKeyHex` override) and consumed by `AccountTransactionSubmitter`. Any change to how the key is resolved, normalized, or applied — including the per-service/per-channel resolution — is security-sensitive; do not refactor without explicit instructions.
