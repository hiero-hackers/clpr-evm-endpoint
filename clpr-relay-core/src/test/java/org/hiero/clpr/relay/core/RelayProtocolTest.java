// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RelayProtocolTest {

    @Test
    void acceptsMatchingVersion() {
        assertThatNoException().isThrownBy(() -> RelayProtocol.validateProtocolVersion(RelayProtocol.PROTOCOL_VERSION));
    }

    @Test
    void rejectsVersionZero() {
        assertThatThrownBy(() -> RelayProtocol.validateProtocolVersion(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocolVersion mismatch")
                .hasMessageContaining("uninitialized");
    }

    @Test
    void rejectsFutureVersion() {
        assertThatThrownBy(() -> RelayProtocol.validateProtocolVersion(99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocolVersion mismatch")
                .hasMessageContaining("99");
    }

    @Test
    void protocolVersionConstantIsOne() {
        assertThat(RelayProtocol.PROTOCOL_VERSION).isEqualTo(1);
    }
}
