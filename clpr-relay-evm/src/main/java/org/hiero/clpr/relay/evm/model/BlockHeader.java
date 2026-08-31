// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm.model;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;

public record BlockHeader(
        Bytes parentHash,
        Bytes sha3Uncles,
        Address miner,
        Bytes stateRoot,
        Bytes transactionsRoot,
        Bytes receiptsRoot,
        Bytes logsBloom, // 256 bytes
        BigInteger difficulty,
        BigInteger number,
        BigInteger gasLimit,
        BigInteger gasUsed,
        BigInteger timestamp,
        Bytes extraData,
        Bytes mixHash,
        Bytes nonce,

        // EIP-1559 (London) — null on pre-London blocks
        BigInteger baseFeePerGas,

        // EIP-4895 (Shanghai) — null on pre-Shanghai blocks
        Bytes withdrawalsRoot,

        // EIP-4844 (Cancun) — null on pre-Cancun blocks. Required to keep header RLP
        // field count in sync with Besu's serialization (which writes blob fields BEFORE
        // parentBeaconBlockRoot); omitting them produces a different keccak digest and
        // the QBFT committed-seal recovery fails.
        BigInteger blobGasUsed,
        BigInteger excessBlobGas,

        // EIP-4788 (Cancun) — null on pre-Cancun blocks
        Bytes parentBeaconBlockRoot,

        // EIP-7685 (Prague) — null on pre-Prague blocks
        Bytes requestsHash,

        // Derived — not an RLP field, but always carried alongside
        Bytes blockHash) {}
