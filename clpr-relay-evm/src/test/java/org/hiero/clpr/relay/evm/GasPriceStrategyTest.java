// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GasPriceStrategyTest {

    @Test
    void computesEip1559GasPrice() {
        var strategy = new GasPriceStrategy(2_000_000_000L, 2.0, Long.MAX_VALUE);
        var result = strategy.compute(10_000_000_000L); // 10 gwei base
        // maxFeePerGas = 10 * 2.0 + 2 = 22 gwei
        assertThat(result.maxFeePerGas()).isEqualTo(22_000_000_000L);
        assertThat(result.maxPriorityFeePerGas()).isEqualTo(2_000_000_000L);
    }

    @Test
    void respectsMaxGasPriceCap() {
        var strategy = new GasPriceStrategy(2_000_000_000L, 2.0, 15_000_000_000L);
        var result = strategy.compute(10_000_000_000L);
        assertThat(result.maxFeePerGas()).isEqualTo(15_000_000_000L);
    }

    @Test
    void zeroBaseFee() {
        var strategy = new GasPriceStrategy(2_000_000_000L, 2.0, Long.MAX_VALUE);
        var result = strategy.compute(0L);
        assertThat(result.maxFeePerGas()).isEqualTo(2_000_000_000L); // just priority fee
    }
}
