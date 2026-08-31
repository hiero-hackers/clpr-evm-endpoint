#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Tears down the kind cluster created by kind-setup.sh.

set -euo pipefail

CLUSTER_NAME="${KIND_CLUSTER:-clpr-dev}"

echo "=== CLPR Kind Teardown ==="

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
    kind delete cluster --name "$CLUSTER_NAME"
    echo "  Deleted cluster $CLUSTER_NAME"
else
    echo "  Cluster $CLUSTER_NAME not found — nothing to delete."
fi

echo "Done."
