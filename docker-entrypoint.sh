#!/usr/bin/env sh
# SPDX-License-Identifier: Apache-2.0
set -e

# Inject the signing private key into the relay config at container start so it
# never lives in the ConfigMap (the config is a ConfigMap; the key is a Secret).
# The mounted relay.yaml carries a __RELAY_SIGNING_KEY__ sentinel for each
# service's defaultSigningPrivateKeyHex; we substitute the Secret-sourced
# RELAY_SIGNING_KEY into a writable copy under /tmp (an emptyDir — the root
# filesystem is read-only) and point the relay at that copy via -Drelay.configFile.
# An unset/empty key substitutes to "", so the loader fails fast for any service
# that needs a key rather than starting with a bogus one.
CONFIG_SRC="${RELAY_CONFIG_SRC:-/app/relay.yaml}"
CONFIG_DST="${RELAY_CONFIG_DST:-/tmp/relay.yaml}"
CONFIG_PROP=""
if [ -f "${CONFIG_SRC}" ]; then
    cp "${CONFIG_SRC}" "${CONFIG_DST}"
    # Substitute every SIGNING_KEY_* env var into its matching __SIGNING_KEY_*__ sentinel.
    # Sentinels are address-keyed (service address) or channel-id-keyed (per-channel
    # override). secp256k1 keys are hex so contain no '#' — safe sed delimiter.
    for var in $(printenv | sed -n 's/^\(SIGNING_KEY_[^=]*\)=.*/\1/p'); do
        val=$(printenv "${var}")
        sed -i "s#__${var}__#${val}#g" "${CONFIG_DST}"
    done
    CONFIG_PROP="-Drelay.configFile=${CONFIG_DST}"
fi

# shellcheck disable=SC2086
exec java \
    ${JAVA_OPTS:-} \
    ${CONFIG_PROP} \
    -p /app/lib \
    -m org.hiero.clpr.relay.app/org.hiero.clpr.relay.app.ClprRelayMain \
    "$@"
