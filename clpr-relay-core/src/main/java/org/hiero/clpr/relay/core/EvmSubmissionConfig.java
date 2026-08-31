// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

/**
 * Shared configuration constants for EVM transaction submission.
 *
 * <p>Centralised here so that both the evm module ({@code AccountTransactionSubmitter}) and the
 * grpc module ({@code ClprEndpointClient}) can reference the same values without a
 * cross-module dependency between them.
 */
public final class EvmSubmissionConfig {

    /**
     * Number of receipt-poll attempts per submit round. Deliberately spans more than one block
     * period (attempts * {@link #RECEIPT_POLL_SLEEP_MS} &gt; the chain's block time) so a freshly
     * broadcast transaction can normally be observed mined within a single round rather than forcing
     * a re-send.
     */
    public static final int RECEIPT_POLL_ATTEMPTS = 50;

    /** Milliseconds to sleep between receipt poll attempts. */
    public static final long RECEIPT_POLL_SLEEP_MS = 100;

    /** Milliseconds to sleep before retrying a transient (non-definite) send failure. */
    public static final long SUBMIT_RETRY_SLEEP_MS = 250;

    private EvmSubmissionConfig() {}
}
