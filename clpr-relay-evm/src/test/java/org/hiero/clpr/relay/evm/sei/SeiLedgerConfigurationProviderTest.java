// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.sei;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hiero.clpr.relay.core.CommitmentLevel;
import org.hiero.clpr.relay.core.ContractStateReader;
import org.hiero.clpr.relay.evm.jsonrpc.EvmJsonRpcClient;
import org.hiero.clpr.relay.evm.model.BlockHeader;
import org.hiero.clpr.relay.evm.testfixtures.TestEvmJsonRpcClient;
import org.junit.jupiter.api.Test;

class SeiLedgerConfigurationProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ZERO_HASH = "0x" + "00".repeat(32);
    private static final String ONE_HASH = "0x" + "00".repeat(31) + "01";
    private static final String MINER = "0x" + "ab".repeat(20);

    /** Stub RPC client that returns a specific parsed header per requested block tag. */
    private static final class StubRpcClient extends TestEvmJsonRpcClient {
        private final Map<String, BlockHeader> headersByTag = new HashMap<>();

        void setBlock(final String tag, final JsonNode block) {
            headersByTag.put(tag, EvmJsonRpcClient.blockHeaderFromJson(block));
        }

        @Override
        public BlockHeader ethGetBlockHeaderByNumber(final String blockTag) {
            final BlockHeader h = headersByTag.get(blockTag);
            if (h == null) {
                throw new AssertionError("unexpected block tag requested: " + blockTag);
            }
            return h;
        }
    }

    /**
     * Stub state reader that returns a fixed configuration and records the exact block tag
     * the provider used — must be the resolved hex number, not any symbolic tag.
     */
    private static final class StubStateReader implements ContractStateReader {
        private final ClprLedgerConfiguration config;
        String lastBlockTag;

        StubStateReader(final ClprLedgerConfiguration config) {
            this.config = config;
        }

        @Override
        public Optional<ClprChannel> readChannelState(final Bytes channelId, final String blockTag) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<QueuedMessage> readQueuedMessages(
                final Bytes channelId, final long fromId, final long toId, final String blockTag) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(final CommitmentLevel commitmentLevel) {
            throw new AssertionError("provider must use the string-tag overload to pin the read to an exact block; got "
                    + commitmentLevel);
        }

        @Override
        public ClprLedgerConfiguration readLedgerConfiguration(final String blockTag) {
            this.lastBlockTag = blockTag;
            return config;
        }
    }

    /** Stub CometBFT client that returns a pre-configured validator set and records the height. */
    private static final class StubCometBftRpcClient extends CometBftRpcClient {
        SeiValidatorSet validatorSetResponse;
        long lastValidatorSetHeight;

        StubCometBftRpcClient() {
            super("http://localhost:1", 0, Duration.ofSeconds(1));
        }

        @Override
        public SeiValidatorSet getValidatorSet(final long height) {
            this.lastValidatorSetHeight = height;
            return validatorSetResponse;
        }
    }

    @Test
    void provide_assemblesPayloadWithValidatorSet() throws Exception {
        final var config = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(1)
                .chainId("atlantic-2")
                .serviceAddress(Bytes.wrap(new byte[] {0x12, 0x34}))
                .throttles(ClprThrottles.newBuilder().maxMessagesPerBundle(50).build())
                .build();
        final var stateReader = new StubStateReader(config);

        final var rpc = new StubRpcClient();
        // "finalized" resolves to block 0x100 = 256
        rpc.setBlock("finalized", block("0x100", ONE_HASH, "0xdeadbeef", "0x65111111"));

        final var validatorSet = SeiValidatorSet.newBuilder()
                .validators(List.of(SeiValidatorEntry.newBuilder()
                        .ed25519PubKey(Bytes.wrap(new byte[32]))
                        .votingPower(100L)
                        .build()))
                .build();

        final var cometBftRpcClient = new StubCometBftRpcClient();
        cometBftRpcClient.validatorSetResponse = validatorSet;

        final var provider = new SeiLedgerConfigurationProvider(rpc, stateReader, cometBftRpcClient);
        final var sei = provider.provide(CommitmentLevel.FINALIZED).sei();

        // Block pinning: contract read must use the exact resolved hex number, not the symbolic tag.
        assertThat(stateReader.lastBlockTag).isEqualTo("0xff");

        assertThat(sei.ledgerConfiguration()).isEqualTo(config);
        assertThat(sei.initialValidatorSet()).isEqualTo(validatorSet);
        assertThat(sei.initialValidatorSetHeight()).isEqualTo(256);
        // Validator set must be fetched at stateHeight + 1 = 0xff + 1 = 257.
        assertThat(cometBftRpcClient.lastValidatorSetHeight).isEqualTo(256L);
    }

    @Test
    void provide_pinsContractReadToExactCurrentBlockNumber() throws Exception {
        final var stateReader = new StubStateReader(ClprLedgerConfiguration.DEFAULT);

        final var rpc = new StubRpcClient();
        // Symbolic "latest" tag resolves to block 0xabc = 2748.
        rpc.setBlock("latest", block("0xabc", ONE_HASH, "0xdeadbeef", "0x65111111"));

        final var cometBftRpcClient = new StubCometBftRpcClient();
        cometBftRpcClient.validatorSetResponse = SeiValidatorSet.DEFAULT;

        final var provider = new SeiLedgerConfigurationProvider(rpc, stateReader, cometBftRpcClient);
        provider.provide(CommitmentLevel.LATEST);

        // Provider must NOT pass "latest" to the contract read — it must pass the resolved hex
        // block number from the eth_getBlockByNumber result.
        assertThat(stateReader.lastBlockTag).isEqualTo("0xabb");
    }

    private static JsonNode block(
            final String number, final String hash, final String extraData, final String timestamp) throws Exception {
        return MAPPER.readTree("""
                {
                  "parentHash":            "%s",
                  "sha3Uncles":            "%s",
                  "miner":                 "%s",
                  "stateRoot":             "%s",
                  "transactionsRoot":      "%s",
                  "receiptsRoot":          "%s",
                  "logsBloom":             "0x%s",
                  "difficulty":            "0x00",
                  "number":                "%s",
                  "gasLimit":              "0x1000",
                  "gasUsed":               "0x800",
                  "timestamp":             "%s",
                  "extraData":             "%s",
                  "mixHash":               "%s",
                  "nonce":                 "0x0000000000000000",
                  "baseFeePerGas":         "0x07",
                  "withdrawalsRoot":       null,
                  "parentBeaconBlockRoot": null,
                  "hash":                  "%s"
                }
                """.formatted(
                        ZERO_HASH,
                        ZERO_HASH,
                        MINER,
                        ZERO_HASH,
                        ZERO_HASH,
                        ZERO_HASH,
                        "00".repeat(256),
                        number,
                        timestamp,
                        extraData,
                        ZERO_HASH,
                        hash));
    }
}
