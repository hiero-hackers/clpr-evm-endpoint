#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Invoked by semantic-release prepareCmd. Stamps the resolved version into
# version.txt and charts/Chart.yaml. The committed files are picked up by
# the @semantic-release/git plugin and pushed back as part of the release
# commit, then consumed by the publish step that follows.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <version>" >&2
  exit 2
fi

VERSION="$1"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

printf '%s' "${VERSION}" > version.txt

# Update both `version:` and `appVersion:` in the chart manifest. `version:` is
# the chart's own version (semver); `appVersion:` is the app shipped by the
# chart — they track together for this single-app chart.
python3 - "${VERSION}" <<'PY'
import re, sys, pathlib
version = sys.argv[1]
path = pathlib.Path("charts/Chart.yaml")
text = path.read_text()
text = re.sub(r'^version:\s*.*$', f'version: {version}', text, count=1, flags=re.M)
text = re.sub(r'^appVersion:\s*.*$', f'appVersion: "{version}"', text, count=1, flags=re.M)
path.write_text(text)
PY

echo "Stamped version ${VERSION} into version.txt and charts/Chart.yaml"
