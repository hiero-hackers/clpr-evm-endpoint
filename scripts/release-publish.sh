#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Invoked by semantic-release publishCmd. Expects the caller to have already:
#   - authenticated docker to ${JFROG_REGISTRY} (e.g. via OIDC token + docker login)
#   - authenticated helm to  ${JFROG_REGISTRY} (e.g. via OIDC token + helm registry login)
#   - configured QEMU + buildx for multi-arch builds
#
# Required env:
#   JFROG_REGISTRY       host, e.g. artifacts.hashgraph.io
#   JFROG_DOCKER_REPO    docker OCI repo path, e.g. clpr-evm-endpoint-docker-release
#   JFROG_HELM_REPO      helm   OCI repo path, e.g. clpr-evm-endpoint-helm-release
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <version>" >&2
  exit 2
fi

VERSION="$1"
: "${JFROG_REGISTRY:?JFROG_REGISTRY must be set}"
: "${JFROG_DOCKER_REPO:?JFROG_DOCKER_REPO must be set}"
: "${JFROG_HELM_REPO:?JFROG_HELM_REPO must be set}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

IMAGE_BASE="${JFROG_REGISTRY}/${JFROG_DOCKER_REPO}/clpr-evm-endpoint"
IMAGE_REF="${IMAGE_BASE}:${VERSION}"
LATEST_REF="${IMAGE_BASE}:latest"
HELM_REGISTRY_REF="oci://${JFROG_REGISTRY}/${JFROG_HELM_REPO}"
CHART_NAME="clpr-evm-endpoint"
CHART_TGZ="bin/${CHART_NAME}-${VERSION}.tgz"

# Skip the `latest` tag for prerelease versions (anything with a `-` suffix
# per semver — e.g. 1.2.3-alpha.1, 1.2.3-rc.2). `latest` should only ever
# point at the most recent stable release.
TAG_LATEST=true
if [[ "${VERSION}" == *-* ]]; then
  TAG_LATEST=false
fi

echo "==> Building distribution"
./gradlew :clpr-relay-app:installDist --no-daemon

echo "==> Building and pushing multi-arch container image: ${IMAGE_REF}"
if [[ "${TAG_LATEST}" == "true" ]]; then
  echo "    also tagging: ${LATEST_REF}"
fi
mkdir -p bin
BUILD_TAGS=(--tag "${IMAGE_REF}")
if [[ "${TAG_LATEST}" == "true" ]]; then
  BUILD_TAGS+=(--tag "${LATEST_REF}")
fi
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  "${BUILD_TAGS[@]}" \
  --metadata-file bin/container-image.metadata.json \
  --push \
  .
jq -r '."containerimage.digest"' bin/container-image.metadata.json > bin/container-image.digest
echo "    digest: $(cat bin/container-image.digest)"

echo "==> Packaging helm chart: ${CHART_TGZ}"
helm package charts/ \
  --destination bin/ \
  --version "${VERSION}" \
  --app-version "${VERSION}"

echo "==> Pushing helm chart to ${HELM_REGISTRY_REF}"
helm push "${CHART_TGZ}" "${HELM_REGISTRY_REF}" 2>&1 | tee bin/helm-push.log
# Extract the OCI digest from helm push output (format: "Digest: sha256:...").
grep -oE 'sha256:[a-f0-9]{64}' bin/helm-push.log | head -n1 > bin/helm-oci.digest
echo "    digest: $(cat bin/helm-oci.digest)"

echo "==> Release ${VERSION} published"
