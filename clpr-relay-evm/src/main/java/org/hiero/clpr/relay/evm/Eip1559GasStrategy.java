// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import java.math.BigInteger;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.model.BlockHeader;
import org.jspecify.annotations.NonNull;

/**
 * Compute EIP-1559 transaction fees ({@code maxPriorityFeePerGas}, {@code maxFeePerGas}) from
 * the current chain {@code baseFeePerGas} and operator-configured policy.
 *
 * <p>The {@code maxFeePerGas} is {@code round(baseFee * gasBufferMultiplier) + gasPriorityFee},
 * capped at {@code maxGasPriceCap}. Hitting the cap is treated as a hard error: the strategy
 * throws {@link JsonRpcException} so the surrounding submitter records the bundle as
 * {@code FAILED} rather than overpaying.
 *
 * <p>On chains that have no {@code baseFeePerGas} (pre-Merge, non-1559 networks), the field is
 * treated as zero — the resulting {@code maxFeePerGas} equals {@code gasPriorityFee}, which is
 * the right thing for Anvil dev chains.
 */
public final class Eip1559GasStrategy {

    private static final Logger LOGGER = Loggers.getLogger(Eip1559GasStrategy.class);

    /** Result of a single fee computation. */
    public record Fees(long maxPriorityFeePerGas, long maxFeePerGas) {}

    private final EvmJsonRpcClient rpcClient;
    private final long maxGasPriceCap;
    private final long gasPriorityFee;
    private final double gasBufferMultiplier;

    public Eip1559GasStrategy(
            @NonNull final EvmJsonRpcClient rpcClient,
            final long maxGasPriceCap,
            final long gasPriorityFee,
            final double gasBufferMultiplier) {
        if (maxGasPriceCap <= 0L) {
            throw new IllegalArgumentException("maxGasPriceCap must be positive, got " + maxGasPriceCap);
        }
        if (gasPriorityFee < 0L) {
            throw new IllegalArgumentException("gasPriorityFee must be non-negative, got " + gasPriorityFee);
        }
        if (!(gasBufferMultiplier > 0.0)) {
            throw new IllegalArgumentException("gasBufferMultiplier must be positive, got " + gasBufferMultiplier);
        }
        this.rpcClient = rpcClient;
        this.maxGasPriceCap = maxGasPriceCap;
        this.gasPriorityFee = gasPriorityFee;
        this.gasBufferMultiplier = gasBufferMultiplier;
    }

    /**
     * The hard ceiling on {@code maxFeePerGas} (wei). Callers that bump the fee on a re-send clamp
     * to this value so a re-priced replacement never exceeds operator policy.
     *
     * @return the configured gas-price cap in wei
     */
    public long maxGasPriceCap() {
        return maxGasPriceCap;
    }

    /**
     * Compute the fee pair for a transaction submitted at the current chain head.
     *
     * @return the computed {@link Fees}
     * @throws JsonRpcException if the resulting {@code maxFeePerGas} exceeds {@code maxGasPriceCap}
     */
    @NonNull
    public Fees computeFees() {
        final long baseFee = fetchBaseFee();
        final long bufferedBase = Math.round((double) baseFee * gasBufferMultiplier);
        // Detect long overflow on the add (rare; only with extreme baseFee values).
        final long maxFee = bufferedBase + gasPriorityFee;
        final boolean overflowed = bufferedBase > 0L && gasPriorityFee > 0L && maxFee < bufferedBase;
        if (overflowed || maxFee > maxGasPriceCap) {
            LOGGER.warn(
                    "computeFees: maxFeePerGas={} exceeds cap={} (baseFee={}, multiplier={}, priority={})",
                    maxFee,
                    maxGasPriceCap,
                    baseFee,
                    gasBufferMultiplier,
                    gasPriorityFee);
            throw new JsonRpcException(
                    -32000,
                    "gas price cap exceeded: maxFeePerGas=" + maxFee + " > cap=" + maxGasPriceCap + " (baseFee="
                            + baseFee + ")");
        }
        return new Fees(gasPriorityFee, maxFee);
    }

    /**
     * Compute the fee pair for re-pricing a transaction already in flight, clamped to
     * {@code maxGasPriceCap} instead of throwing. Used on a re-send when the live base fee has risen:
     * the caller already holds a pooled transaction at a lower fee, so a market fee above the cap is
     * pinned at the cap (never overpay, never abandon a valid tx) rather than failing the request the
     * way {@link #computeFees()} does for the initial send.
     *
     * @return the computed {@link Fees}, with {@code maxFeePerGas} clamped to {@code maxGasPriceCap}
     */
    @NonNull
    public Fees computeFeesCapped() {
        final long baseFee = fetchBaseFee();
        final long bufferedBase = Math.round((double) baseFee * gasBufferMultiplier);
        final long maxFee = bufferedBase + gasPriorityFee;
        final boolean overflowed = bufferedBase > 0L && gasPriorityFee > 0L && maxFee < bufferedBase;
        final long capped = (overflowed || maxFee > maxGasPriceCap) ? maxGasPriceCap : maxFee;
        // Clamp priority too: an extremely low cap could otherwise leave priority > maxFee (invalid).
        return new Fees(Math.min(gasPriorityFee, capped), capped);
    }

    private long fetchBaseFee() {
        final BlockHeader blockHeader = rpcClient.ethGetBlockHeaderByNumber("latest");
        if (blockHeader == null) {
            throw new JsonRpcException(-32000, "fetchBaseFee: RPC returned null for 'latest' block");
        }
        final BigInteger baseFee = blockHeader.baseFeePerGas();
        // Pre-London / non-1559 chains (e.g. Anvil dev defaults) leave this field null.
        // Treat baseFee as 0 so maxFeePerGas == gasPriorityFee — see the class javadoc.
        return baseFee == null ? 0L : baseFee.longValueExact();
    }
}
