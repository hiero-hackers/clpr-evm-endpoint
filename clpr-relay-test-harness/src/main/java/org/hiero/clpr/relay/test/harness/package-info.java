// SPDX-License-Identifier: Apache-2.0
/**
 * Single-side stub-peer test harness for CLPR endpoint integration tests.
 *
 * <p>The harness drives one real endpoint-under-test against a real CLPR Service (the oracle) while
 * replacing the peer with a controllable {@code StubPeer} and driving the ledger directly through a
 * {@code ChainTxSubmitter} (default {@code Eip1559TxSubmitter}) — the on-chain actor
 * ({@code ContractInteractor}) is parameterized by it, so it works against any chain independently of
 * the relay. See {@code docs/superpowers/plans/2026-06-18-stub-peer-harness-design.md} and the spec test
 * matrix {@code clpr-channel-lifecycle-test-spec-v2.md}.
 */
package org.hiero.clpr.relay.test.harness;
