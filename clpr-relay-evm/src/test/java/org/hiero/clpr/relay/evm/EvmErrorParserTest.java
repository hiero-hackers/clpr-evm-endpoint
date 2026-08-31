// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.logging.api.Level;
import org.junit.jupiter.api.Test;

class EvmErrorParserTest {

    @Test
    void resolvesKnownClprSelectors() {
        assertThat(EvmErrorParser.errorName("0xcd21c020")).isEqualTo("ClprRunningHashMismatch()");
        assertThat(EvmErrorParser.errorName("0x24315cae")).isEqualTo("ClprReplayDetected()");
        assertThat(EvmErrorParser.errorName("0xae5f8b75")).isEqualTo("ClprEndpointNotRegistered()");
        assertThat(EvmErrorParser.errorName("0xb60323c7")).isEqualTo("ClprEndpointManifestFull()");
        assertThat(EvmErrorParser.errorName("0xc165a74c")).isEqualTo("ClprEndpointUnauthorized()");
    }

    @Test
    void unreachableErrorsAreExcludedFromTheRegistry() {
        // Errors reachable only from functions the relay never calls (connector/channel/endpoint
        // registration, messaging, admin) and the Ethereum-beacon verifier are not tracked.
        assertThat(EvmErrorParser.errorName("0xffac0415")).isNull(); // ClprInsufficientStake()
        assertThat(EvmErrorParser.errorName("0xa1a32afc")).isNull(); // ClprMessageAlreadyRedacted()
        assertThat(EvmErrorParser.errorName("0x6adcbd47")).isNull(); // InvalidSyncAggregate() (beacon)
    }

    @Test
    void resolvesVerifierAndBuiltinSelectors() {
        assertThat(EvmErrorParser.errorName("0x08c379a0")).isEqualTo("Error(string)");
        assertThat(EvmErrorParser.errorName("0x4e487b71")).isEqualTo("Panic(uint256)");
        assertThat(EvmErrorParser.errorName("0x5c427cd9")).isEqualTo("UnauthorizedCaller()");
    }

    @Test
    void selectorLookupIsCaseInsensitive() {
        assertThat(EvmErrorParser.errorName("0xCD21C020")).isEqualTo("ClprRunningHashMismatch()");
    }

    @Test
    void unknownSelectorResolvesToNull() {
        assertThat(EvmErrorParser.errorName("0xdeadbeef")).isNull();
        assertThat(EvmErrorParser.errorName(null)).isNull();
    }

    @Test
    void extractsSelectorFromJsonRpcMessage() {
        final String msg = "JSON-RPC error 3: execution reverted data=\"0xcd21c020\"";
        assertThat(EvmErrorParser.extractSelector(msg)).isEqualTo("0xcd21c020");
        assertThat(EvmErrorParser.extractSelector("execution reverted data=\"0x\""))
                .isNull();
        assertThat(EvmErrorParser.extractSelector(null)).isNull();
    }

    @Test
    void jsonRpcRevertIsAKnownContractErrorLoggedAtWarn() {
        final EvmError error = EvmErrorParser.parse(new JsonRpcException(3, "execution reverted data=\"0xcd21c020\""));

        assertThat(error.isKnown()).isTrue();
        assertThat(error.selector()).isEqualTo("0xcd21c020");
        assertThat(error.errorName()).isEqualTo("ClprRunningHashMismatch()");
        assertThat(error.level()).isEqualTo(Level.WARN);
        assertThat(error.is(EvmErrorParser.CLPR_RUNNING_HASH_MISMATCH)).isTrue();
        assertThat(error.is(EvmErrorParser.CLPR_REPLAY_DETECTED)).isFalse();
        assertThat(error.describe()).isEqualTo("ClprRunningHashMismatch() [0xcd21c020]");
    }

    @Test
    void jsonRpcRevertWithUnknownSelectorStaysWarn() {
        final EvmError error = EvmErrorParser.parse(new JsonRpcException(3, "reverted data=\"0xdeadbeef\""));

        assertThat(error.isKnown()).isFalse();
        assertThat(error.level()).isEqualTo(Level.WARN);
        assertThat(error.describe()).isEqualTo("unrecognized contract error [0xdeadbeef]");
    }

    @Test
    void jsonRpcRevertWithEmptyDataHasNoSelector() {
        final EvmError error = EvmErrorParser.parse(new JsonRpcException(3, "execution reverted data=\"0x\""));

        assertThat(error.selector()).isNull();
        assertThat(error.level()).isEqualTo(Level.WARN);
        assertThat(error.describe()).isEqualTo("JSON-RPC error 3: execution reverted data=\"0x\"");
    }

    @Test
    void nullErrorHasNoSelectorAndFixedDescription() {
        final EvmError error = EvmErrorParser.parse(null);

        assertThat(error.isKnown()).isFalse();
        assertThat(error.selector()).isNull();
        assertThat(error.level()).isEqualTo(Level.WARN);
        assertThat(error.describe()).isEqualTo("contract reverted without error data");
    }
}
