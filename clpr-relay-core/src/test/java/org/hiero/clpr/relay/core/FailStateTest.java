// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.clpr.relay.core.FailState.FailureDecision;
import org.junit.jupiter.api.Test;

class FailStateTest {

    /** Construct a fresh throwable so each call carries its own (still-stable-shaped) stacktrace. */
    private static RuntimeException boom() {
        return new IllegalStateException("boom");
    }

    // -------------------------------------------------------------------------
    // Backoff
    // -------------------------------------------------------------------------

    @Test
    void backoffDoublesAndCaps() {
        final var state = new FailState(100L, 800L);
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(100L); // 100 << 0
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(200L); // 100 << 1
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(400L); // 100 << 2
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(800L); // 100 << 3 == cap
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(800L); // capped
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(800L); // stays capped
    }

    @Test
    void streakIsEpisodeWideRegardlessOfExceptionType() {
        // A type change mid-episode must NOT reset the exponent — only success does.
        final var state = new FailState(10L, 100_000L);
        assertThat(state.onFailure(new IllegalStateException()).backoffMs()).isEqualTo(10L); // streak 1
        assertThat(state.onFailure(new IllegalStateException()).backoffMs()).isEqualTo(20L); // streak 2
        assertThat(state.onFailure(new IllegalArgumentException()).backoffMs()).isEqualTo(40L); // streak 3
        assertThat(state.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void backoffDoesNotOverflowWithLargeStreak() {
        final var state = new FailState(1_000L, 30_000L);
        long last = 0;
        for (int i = 0; i < 100; i++) {
            last = state.onFailure(boom()).backoffMs();
        }
        assertThat(last).isEqualTo(30_000L); // pinned at cap, never negative/overflowed
    }

    // -------------------------------------------------------------------------
    // Instant reset (two-state machine)
    // -------------------------------------------------------------------------

    @Test
    void firstSuccessClearsEverythingAndReturnsStreak() {
        final var state = new FailState(100L, 30_000L);
        state.onFailure(boom());
        state.onFailure(boom());
        state.onFailure(boom());
        assertThat(state.consecutiveFailures()).isEqualTo(3);
        assertThat(state.isFailing()).isTrue();

        assertThat(state.onSuccess()).isEqualTo(3); // returns the cleared streak
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.isFailing()).isFalse();

        // Backoff returns to base on the next failure (exponent reset).
        assertThat(state.onFailure(boom()).backoffMs()).isEqualTo(100L);
    }

    @Test
    void successWhileHealthyReturnsZero() {
        final var state = new FailState(100L, 30_000L);
        assertThat(state.onSuccess()).isZero();
        state.onFailure(boom());
        assertThat(state.onSuccess()).isEqualTo(1);
        assertThat(state.onSuccess()).isZero(); // already healthy
    }

    // -------------------------------------------------------------------------
    // Logging decision (full-then-compact, fingerprint-driven)
    // -------------------------------------------------------------------------

    @Test
    void firstOccurrenceLogsFullRepeatsLogCompact() {
        // A real loop throws from the same site each iteration → identical stack → same fingerprint.
        // Model that by reusing one instance (its captured stack is fixed at construction).
        final var fault = boom();
        final var state = new FailState(100L, 30_000L);
        final FailureDecision first = state.onFailure(fault);
        assertThat(first.logFullDetail()).isTrue();
        assertThat(first.consecutiveFailures()).isEqualTo(1);

        final FailureDecision second = state.onFailure(fault);
        assertThat(second.logFullDetail()).isFalse(); // same fingerprint → compact
        assertThat(second.consecutiveFailures()).isEqualTo(2);
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void newFingerprintMidEpisodeLogsFullAgain() {
        final var faultA = new IllegalStateException("a");
        final var faultB = new ArithmeticException("b");
        final var state = new FailState(100L, 30_000L);
        final String fpA = state.onFailure(faultA).fingerprint();
        state.onFailure(faultA); // repeat → compact

        // A genuinely different fault shape (different class) gets its own full line.
        final FailureDecision other = state.onFailure(faultB);
        assertThat(other.logFullDetail()).isTrue();
        assertThat(other.fingerprint()).isNotEqualTo(fpA);
    }

    @Test
    void fingerprintSetClearsOnSuccess() {
        final var fault = boom();
        final var state = new FailState(100L, 30_000L);
        assertThat(state.onFailure(fault).logFullDetail()).isTrue();
        assertThat(state.onFailure(fault).logFullDetail()).isFalse();
        state.onSuccess();
        // New episode: the same fault logs full again.
        assertThat(state.onFailure(fault).logFullDetail()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsInvalidBackoffConfig() {
        assertThatThrownBy(() -> new FailState(0L, 100L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailState(100L, 50L)).isInstanceOf(IllegalArgumentException.class);
    }
}
