// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.testfixtures;

import static org.hiero.clpr.relay.evm.ByteUtils.ZERO_HASH;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.time.Duration;
import org.hiero.clpr.relay.evm.AbiCodec;
import org.hiero.clpr.relay.evm.JsonRpcException;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.model.Address;
import org.hiero.clpr.relay.evm.model.BlockHeader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An {@link EvmJsonRpcClient} carrying the retry/timeout defaults the test suites assume.
 * Production no longer ships these defaults (they come from {@code RelayConfig}), so both the
 * {@code clpr-relay-evm} unit tests and the {@code clpr-relay-integration-tests} suite share this
 * one fixture — instantiating it directly, or subclassing it to stub individual RPC methods.
 *
 * It also provides a couple of convenience methods for specific use cases.
 */
public class TestEvmJsonRpcClient extends EvmJsonRpcClient {

    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String DEFAULT_URL = "http://localhost:8545";

    public TestEvmJsonRpcClient() {
        this(DEFAULT_URL);
    }

    public TestEvmJsonRpcClient(final String jsonRpcUrl) {
        super(jsonRpcUrl, DEFAULT_MAX_RETRIES, DEFAULT_REQUEST_TIMEOUT);
    }

    /** Stub that returns a block whose {@code baseFeePerGas} is the given long, hex-encoded. */
    public static EvmJsonRpcClient stubWithBaseFee(@Nullable final Long baseFeeWei) {
        return new EvmJsonRpcClient(DEFAULT_URL, DEFAULT_MAX_RETRIES, DEFAULT_REQUEST_TIMEOUT) {
            @Override
            public BlockHeader ethGetBlockHeaderByNumber(final String blockTag) {
                if (baseFeeWei != null) {
                    return minimalBlockHeader(BigInteger.valueOf(baseFeeWei));
                } else {
                    return minimalBlockHeader(BigInteger.ZERO);
                }
            }
        };
    }

    /** Returns a fixed block header so the loop can resolve a block to read against. */
    public static EvmJsonRpcClient newStubClient(String url) {

        return new EvmJsonRpcClient(url, DEFAULT_MAX_RETRIES, DEFAULT_REQUEST_TIMEOUT) {
            @Override
            public BlockHeader ethGetBlockHeaderByNumber(final String blockTag) {
                return minimalBlockHeader(BigInteger.ZERO);
            }
        };
    }

    public static @NonNull EvmJsonRpcClient newStubClient(final byte[] response) {
        return new EvmJsonRpcClient(DEFAULT_URL, DEFAULT_MAX_RETRIES, DEFAULT_REQUEST_TIMEOUT) {
            @Override
            public String ethCall(final String to, final String data, final String blockTag) {
                return AbiCodec.toHex(response);
            }
        };
    }

    /** Returns a fixed hex string for any ethCallFrom — simulates a successful submit. */
    public static class SuccessRpcClient extends EvmJsonRpcClient {
        private final String returnHex;
        public String lastFrom;
        public String lastTo;

        public SuccessRpcClient(final String returnHex) {
            super(DEFAULT_URL, DEFAULT_MAX_RETRIES, DEFAULT_REQUEST_TIMEOUT);
            this.returnHex = returnHex;
        }

        @Override
        public String ethCallFrom(final String from, final String to, final String data, final String blockTag) {
            lastFrom = from;
            lastTo = to;
            return returnHex;
        }
    }

    /** Throws {@link JsonRpcException} for any ethCallFrom — simulates a revert. */
    public static class RevertRpcClient extends EvmJsonRpcClient {
        private final String revertMessage;
        public String lastFrom;
        public String lastTo;

        public RevertRpcClient(final String revertMessage) {
            super(DEFAULT_URL, DEFAULT_MAX_RETRIES, DEFAULT_REQUEST_TIMEOUT);
            this.revertMessage = revertMessage;
        }

        @Override
        public String ethCallFrom(final String from, final String to, final String data, final String blockTag) {
            lastFrom = from;
            lastTo = to;
            throw new JsonRpcException(-32000, revertMessage.isEmpty() ? "revert" : revertMessage);
        }
    }

    private static BlockHeader minimalBlockHeader(BigInteger baseFeePerGas) {
        return new BlockHeader(
                ZERO_HASH,
                ZERO_HASH,
                Address.ZERO,
                ZERO_HASH,
                ZERO_HASH,
                ZERO_HASH,
                ZERO_HASH,
                BigInteger.ZERO,
                BigInteger.ONE,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                Bytes.EMPTY,
                ZERO_HASH,
                ZERO_HASH,
                baseFeePerGas,
                ZERO_HASH,
                BigInteger.ZERO,
                BigInteger.ZERO,
                ZERO_HASH,
                ZERO_HASH,
                ZERO_HASH);
    }
}
