// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

/**
 * Protocol-level constants for the CLPR relay.
 *
 * <p>The relay refuses to operate against a contract whose {@code protocolVersion} field
 * does not match {@link #PROTOCOL_VERSION}. This prevents silent mis-behaviour when a
 * contract upgrade introduces breaking changes that the relay has not been updated for.
 */
public final class RelayProtocol {

    /** The CLPR protocol version implemented by this relay (spec v1). */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * Validates that the on-chain protocol version matches this relay's implementation.
     *
     * @param actual the {@code protocolVersion} decoded from the on-chain ledger configuration
     * @throws IllegalStateException if {@code actual} differs from {@link #PROTOCOL_VERSION}
     *                               or is zero (uninitialized / not populated)
     */
    public static void validateProtocolVersion(final int actual) {
        if (actual != PROTOCOL_VERSION) {
            throw new IllegalStateException("Contract protocolVersion mismatch: relay expects "
                    + PROTOCOL_VERSION
                    + " but contract reports "
                    + actual
                    + ". The relay cannot operate against an incompatible contract."
                    + (actual == 0
                            ? " A value of 0 indicates the field was not populated"
                                    + " or the contract is uninitialized."
                            : ""));
        }
    }

    private RelayProtocol() {}
}
