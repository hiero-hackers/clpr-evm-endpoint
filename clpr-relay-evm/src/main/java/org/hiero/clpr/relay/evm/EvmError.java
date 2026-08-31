// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.evm;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import org.jspecify.annotations.Nullable;

/**
 * A decoded {@code ClprService} contract revert: its 4-byte selector and, when recognised, the
 * Solidity signature and the {@link Level} to log it at.
 *
 * @param selector   the {@code 0x}-prefixed lower-case selector, or {@code null} if the revert had
 *                   no error data
 * @param errorName  the Solidity signature (e.g. {@code "ClprReplayDetected()"}), or {@code null}
 *                   if the selector is absent or unrecognised
 * @param level      the severity to log at
 * @param rawMessage the exception message this was decoded from, or {@code null}
 */
public record EvmError(
        @Nullable String selector,
        @Nullable String errorName,
        Level level,
        @Nullable String rawMessage) {

    /** @return whether the selector matched a known {@code ClprService} error */
    public boolean isKnown() {
        return errorName != null;
    }

    /**
     * @param candidateSelector a {@code 0x}-prefixed selector, or {@code null}
     * @return whether this error's selector equals it, ignoring case ({@code false} if either is
     *     {@code null})
     */
    public boolean is(final @Nullable String candidateSelector) {
        return selector != null && selector.equalsIgnoreCase(candidateSelector);
    }

    /**
     * @return a one-line description: {@code "Name() [0x…]"}, {@code "unrecognized contract error
     *     [0x…]"}, or the raw message when the revert had no error data
     */
    public String describe() {
        if (selector != null) {
            return (errorName != null ? errorName : "unrecognized contract error") + " [" + selector + "]";
        }
        return rawMessage != null ? rawMessage : "contract reverted without error data";
    }

    /**
     * Log this error at its {@link #level()}.
     *
     * @param logger  the logger to emit through
     * @param context what was being attempted (e.g. {@code "bundle PRE-VERIFY reverted"})
     * @param tag     a structured tag identifying the subject
     */
    public void log(final Logger logger, final String context, final String tag) {
        logger.log(level, "{}: {} {}", context, describe(), tag);
    }
}
