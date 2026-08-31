// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.clpr.relay.core.RelayProtocol;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the protocol-version guard used inside {@link RelayInstance#build} rejects
 * ledger configurations whose {@code protocolVersion} is incompatible with this relay.
 *
 * <p>{@code RelayInstance.build()} wires up real EVM infrastructure and is tested in the
 * integration layer; here we exercise the static helper it delegates to so the contract can
 * be unit-tested without a running EVM node.
 */
class ProtocolVersionValidationTest {

    @Test
    void correctVersionIsAccepted() {
        // Should not throw when the version matches.
        RelayProtocol.validateProtocolVersion(RelayProtocol.PROTOCOL_VERSION);
    }

    @Test
    void versionMismatch_throwsIllegalStateException() {
        // Simulate a contract that has been upgraded to a future version.
        assertThatThrownBy(() -> RelayProtocol.validateProtocolVersion(99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocolVersion mismatch")
                .hasMessageContaining(String.valueOf(RelayProtocol.PROTOCOL_VERSION))
                .hasMessageContaining("99");
    }

    @Test
    void versionZero_treatedAsFatalMismatch() {
        // A value of 0 means the field was not populated / contract is uninitialized.
        assertThatThrownBy(() -> RelayProtocol.validateProtocolVersion(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocolVersion mismatch")
                .hasMessageContaining("uninitialized");
    }

    @Test
    void protocolVersionConstantIsOne() {
        assertThat(RelayProtocol.PROTOCOL_VERSION).isEqualTo(1);
    }
}
