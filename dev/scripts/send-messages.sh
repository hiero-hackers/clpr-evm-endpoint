#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# send-messages.sh — Continuously send test messages on both chains in a kind dev deployment.
#
# Reads contract addresses and IDs from the env file written by deploy-contracts.sh, then
# sends one message per chain every $MSG_INTERVAL_MS milliseconds using Anvil account 2
# as a dedicated sender. Using a separate account avoids nonce conflicts with relay A
# (account 0) and relay B (account 1).
#
# Usage (invoked automatically by kind-setup.sh):
#   source /tmp/clpr-kind-setup.env   # already done by kind-setup.sh
#   dev/scripts/send-messages.sh
#
# Environment variables (all optional):
#   ANVIL_URL         JSON-RPC URL for Anvil (default: http://localhost:8545)
#   SETUP_ENV         Path to the env file written by deploy-contracts.sh
#                     (default: /tmp/clpr-kind-setup.env)
#   MSG_INTERVAL_MS   Milliseconds between message pairs (default: 5000)

set -euo pipefail

ANVIL_URL="${ANVIL_URL:-http://localhost:8545}"
SETUP_ENV="${SETUP_ENV:-/tmp/clpr-kind-setup.env}"
INTERVAL_MS="${MSG_INTERVAL_MS:-5000}"

# Load addresses from the env file if not already in the environment.
if [[ -z "${CONTRACT_A:-}" ]]; then
    if [[ ! -f "$SETUP_ENV" ]]; then
        echo "ERROR: env file not found at $SETUP_ENV — run deploy-contracts.sh first." >&2
        exit 1
    fi
    # shellcheck source=/dev/null
    source "$SETUP_ENV"
fi

# Anvil account 2 — dedicated sender; kept separate from relay signing accounts
# so relay-managed nonces (accounts 0 and 1) are never disturbed.
KEY_SENDER="0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a"

INTERVAL_SEC="$((INTERVAL_MS / 1000)).$(printf '%03d' $((INTERVAL_MS % 1000)))"

echo "=== CLPR Kind Message Sender ===" >&2
echo "Anvil:        $ANVIL_URL" >&2
echo "Contract A:   $CONTRACT_A" >&2
echo "Contract B:   $CONTRACT_B" >&2
echo "Channel:   $CHANNEL_ID" >&2
echo "Interval:     ${INTERVAL_MS} ms" >&2
echo "Press Ctrl-C to stop." >&2
echo "" >&2

IDX=0
while true; do
    IDX=$((IDX + 1))

    # Send on chain A: message targets mock app on chain B.
    MSG_A="0x$(printf 'MSG-A-%d' "$IDX" | od -An -tx1 | tr -d ' \n')"
    if cast send "$CONTRACT_A" "sendMessage(bytes32,bytes32,bytes,bytes)" \
            "$CHANNEL_ID" "$CONNECTOR_ID" "$MOCK_APP_B" "$MSG_A" \
            --rpc-url "$ANVIL_URL" --private-key "$KEY_SENDER" >/dev/null 2>&1; then
        echo "  [msg-sender] sent MSG-A-${IDX}" >&2
    else
        echo "  [msg-sender] error sending MSG-A-${IDX}" >&2
    fi

    # Send on chain B: message targets mock app on chain A.
    MSG_B="0x$(printf 'MSG-B-%d' "$IDX" | od -An -tx1 | tr -d ' \n')"
    if cast send "$CONTRACT_B" "sendMessage(bytes32,bytes32,bytes,bytes)" \
            "$CHANNEL_ID" "$CONNECTOR_ID" "$MOCK_APP_A" "$MSG_B" \
            --rpc-url "$ANVIL_URL" --private-key "$KEY_SENDER" >/dev/null 2>&1; then
        echo "  [msg-sender] sent MSG-B-${IDX}" >&2
    else
        echo "  [msg-sender] error sending MSG-B-${IDX}" >&2
    fi

    sleep "$INTERVAL_SEC"
done
