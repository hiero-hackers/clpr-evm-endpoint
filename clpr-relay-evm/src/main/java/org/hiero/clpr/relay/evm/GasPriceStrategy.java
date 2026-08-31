// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

/**
 * Computes EIP-1559 gas parameters ({@code maxFeePerGas} and {@code maxPriorityFeePerGas}).
 *
 * <p>The computation follows:
 * <pre>
 *   maxFeePerGas = min(baseFee * multiplier + priorityFee, maxGasPriceCap)
 *   maxPriorityFeePerGas = priorityFee
 * </pre>
 */
public class GasPriceStrategy {

    private final long priorityFeeWei;
    private final double baseFeeMultiplier;
    private final long maxGasPriceCapWei;

    /**
     * Create a new GasPriceStrategy.
     *
     * @param priorityFeeWei     default priority fee in wei (e.g., 2 gwei = 2_000_000_000)
     * @param baseFeeMultiplier  multiplier applied to the base fee (e.g., 2.0)
     * @param maxGasPriceCapWei  hard cap on {@code maxFeePerGas}; use {@link Long#MAX_VALUE} for no cap
     */
    public GasPriceStrategy(final long priorityFeeWei, final double baseFeeMultiplier, final long maxGasPriceCapWei) {
        this.priorityFeeWei = priorityFeeWei;
        this.baseFeeMultiplier = baseFeeMultiplier;
        this.maxGasPriceCapWei = maxGasPriceCapWei;
    }

    /**
     * Compute EIP-1559 gas parameters for the given current base fee.
     *
     * @param baseFeeWei the current block base fee in wei
     * @return a {@link GasPrice} record with the computed parameters
     */
    public GasPrice compute(final long baseFeeWei) {
        final long uncapped = (long) (baseFeeWei * baseFeeMultiplier) + priorityFeeWei;
        final long maxFeePerGas = Math.min(uncapped, maxGasPriceCapWei);
        return new GasPrice(maxFeePerGas, priorityFeeWei);
    }

    /**
     * Computed EIP-1559 gas price parameters.
     *
     * @param maxFeePerGas         the maximum total fee per gas unit (base fee + priority)
     * @param maxPriorityFeePerGas the maximum priority fee per gas unit (tip to the miner)
     */
    public record GasPrice(long maxFeePerGas, long maxPriorityFeePerGas) {}
}
