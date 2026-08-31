#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# gen-certs.sh — Generate the two dev relays' mTLS ("secure") gRPC CA material.
#
# The relay uses a two-tier mTLS model: the endpoint's durable ECDSA P-384 *CA* is published
# on-chain as ClprEndpoint.tls_certificate, but the relay reads only the CA *key* from disk.
# At startup it mints a fresh Ed25519 leaf signed by that key (SHA384withECDSA) and presents
# the leaf on the wire; peers trust it via PKIX path validation against the CA from the roster.
# For that chain to validate, the on-chain CA cert MUST carry subject CN=x (the leaf
# is issued under that fixed DN). The CA key MUST be EC P-384 — an RSA key fails leaf generation
# with "No installed provider supports this key". Cert is written as X.509 DER, key as PKCS#8 DER.
#
# Writes into dev/certs/ (override with CERT_DIR):
#   <relay>-cert.der   X.509 DER certificate  -> on-chain pin only (NOT read by the relay)
#   <relay>-key.der    PKCS#8 DER private key  -> relay.grpc.sync.tlsKeyPath
#   <relay>-cert.hex   continuous hex of the DER cert, read by deploy-contracts.sh
#
# Run this once before `docker compose up` when SECURE_TRANSPORT=1.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${CERT_DIR:-$SCRIPT_DIR/../certs}"
mkdir -p "$CERT_DIR"

gen() {
    local name="$1"
    local key_pem="$CERT_DIR/$name-key.pem"
    local cert_pem="$CERT_DIR/$name-cert.pem"

    # Self-signed ECDSA P-384 CA cert. -nodes leaves the key unencrypted; the key `req`
    # emits is PKCS#8 PEM on modern openssl, and pkcs8 -topk8 normalizes it to DER below.
    # Subject is the fixed CN=x the relay stamps as the leaf issuer, so the leaf chains to this
    # CA. CA:TRUE/keyCertSign are set for semantic clarity (the relay validates the leaf with
    # this cert as a trust anchor, so its extensions are not actually re-checked).
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-384 -sha384 -days 3650 -nodes \
        -keyout "$key_pem" -out "$cert_pem" -subj "/CN=x" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,digitalSignature" >/dev/null 2>&1

    openssl x509 -in "$cert_pem" -outform DER -out "$CERT_DIR/$name-cert.der"
    openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt \
        -in "$key_pem" -out "$CERT_DIR/$name-key.der"

    # Continuous lowercase hex of the DER cert for on-chain publication via cast.
    xxd -p -c 100000 "$CERT_DIR/$name-cert.der" | tr -d '\n' > "$CERT_DIR/$name-cert.hex"

    rm -f "$key_pem" "$cert_pem"
    # World-readable so the non-root relay user in the container can load them.
    chmod 644 "$CERT_DIR/$name-cert.der" "$CERT_DIR/$name-key.der" "$CERT_DIR/$name-cert.hex"
    echo "  $name: $(wc -c < "$CERT_DIR/$name-cert.der" | tr -d ' ') byte cert, $(wc -c < "$CERT_DIR/$name-key.der" | tr -d ' ') byte key"
}

echo "Generating dev relay TLS material into $CERT_DIR ..."
gen relay-a
gen relay-b
echo "Done."
