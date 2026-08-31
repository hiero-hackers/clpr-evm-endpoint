// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.chain;

/**
 * One deterministic test account: its signing key, the derived EVM address, and the 64-byte
 * uncompressed secp256k1 public key that CLPR's commit-reveal flows sign with.
 *
 * @param index zero-based slot in the {@link Accounts} pool; also its position in the genesis alloc
 * @param privateKeyHex {@code 0x}-prefixed 32-byte secp256k1 private key
 * @param address {@code 0x}-prefixed lowercase EVM address derived from the key
 * @param publicKey64 64-byte uncompressed public key (X||Y, no {@code 0x04} prefix)
 * @param purpose human-readable label recorded at allocation time, so a diagnostics dump can say
 *     which account ran out of gas rather than just printing an address
 */
public record TestAccount(int index, String privateKeyHex, String address, byte[] publicKey64, String purpose) {

    @Override
    public String toString() {
        return "TestAccount[" + index + " " + purpose + " " + address + "]";
    }
}
