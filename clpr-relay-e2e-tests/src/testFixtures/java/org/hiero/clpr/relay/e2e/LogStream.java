// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.testcontainers.containers.output.OutputFrame;

/**
 * An in-memory capture of one container's stdout/stderr.
 *
 * <p>Exists because {@code GenericContainer.getLogs()} is useless exactly when it is needed most:
 * Testcontainers removes the container when a wait strategy fails or when {@code stop()} is called,
 * and the logs go with it — so a failed startup produces an empty log. Attaching a consumer at
 * create time means the output survives the container, which is what makes the failure diagnostics
 * and the "no ERROR-level messages" assertions possible across restarts.
 *
 * <p>Thread-safe: Testcontainers delivers frames from its own thread while tests read.
 */
public final class LogStream {

    /**
     * Matches an ERROR <em>level field</em> rather than the word anywhere in the line.
     *
     * <p>swirlds-logging renders {@code <timestamp> LEVEL [thread] logger - message}, and JUL/Helidon
     * render {@code [LEVEL]} or {@code LEVEL:}. Anchoring to that leading position matters: several
     * suites assert {@code errorLines().isEmpty()}, and an unanchored match would fire on any INFO
     * line whose message merely mentions the word — a false failure that looks like a real one.
     */
    private static final String ERROR_LEVEL_PATTERN = "^(?:[0-9][0-9:.\\-]*[ T][0-9][0-9:.]*\\s+)?\\[?ERROR\\]?[\\s:]";

    private final String source;
    private final List<String> lines = new ArrayList<>();

    /**
     * Bytes of the current, not-yet-terminated line.
     *
     * <p>Testcontainers frames are not line-aligned, so a marker straddling a frame boundary would be
     * missed by both {@link #sawLineContaining} and {@link #errorLines} if each frame were split on its
     * own. Partial text is held here until its newline arrives.
     */
    private final StringBuilder partial = new StringBuilder();

    /**
     * @param source label used in assertion messages, e.g. the container id
     */
    public LogStream(final String source) {
        this.source = source;
    }

    /** @return a consumer to hand to {@code GenericContainer.withLogConsumer}. */
    public Consumer<OutputFrame> consumer() {
        return frame -> {
            final String utf8 = frame.getUtf8String();
            if (utf8 == null || utf8.isEmpty()) {
                return;
            }
            synchronized (lines) {
                partial.append(utf8);
                int newline;
                while ((newline = indexOfNewline(partial)) >= 0) {
                    final String line = partial.substring(0, newline);
                    // Consume the terminator, handling CRLF as one break.
                    int skip = newline + 1;
                    if (partial.charAt(newline) == '\r' && skip < partial.length() && partial.charAt(skip) == '\n') {
                        skip++;
                    }
                    partial.delete(0, skip);
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        };
    }

    private static int indexOfNewline(final CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    /** @return the label this stream was created with. */
    public String source() {
        return source;
    }

    /** @return every captured line, oldest first. */
    public List<String> lines() {
        synchronized (lines) {
            final List<String> out = new ArrayList<>(lines);
            // Include the unterminated tail: a relay killed mid-line still wrote something, and that
            // last fragment is often the one that explains the failure.
            if (!partial.isEmpty()) {
                out.add(partial.toString());
            }
            return List.copyOf(out);
        }
    }

    /** @return the whole capture as one newline-joined string. */
    public String text() {
        return String.join("\n", lines());
    }

    /**
     * Lines containing {@code needle}, case-insensitively.
     *
     * @param needle substring to look for
     * @return the matching lines
     */
    public List<String> containing(final String needle) {
        final String lower = needle.toLowerCase(Locale.ROOT);
        return lines().stream()
                .filter(l -> l.toLowerCase(Locale.ROOT).contains(lower))
                .toList();
    }

    /**
     * Lines matching a regular expression anywhere.
     *
     * @param regex the pattern
     * @return the matching lines
     */
    public List<String> matching(final String regex) {
        final Pattern pattern = Pattern.compile(regex);
        return lines().stream().filter(l -> pattern.matcher(l).find()).toList();
    }

    /**
     * Lines the relay logged at ERROR level.
     *
     * <p>Matches the swirlds-logging console layout, which renders the level as a bare token. Kept
     * deliberately narrow — a substring search for "ERROR" would also match log messages that merely
     * mention the word, and an assertion that fires on its own diagnostics is worse than no
     * assertion.
     *
     * @return the ERROR-level lines
     */
    public List<String> errorLines() {
        return matching(ERROR_LEVEL_PATTERN);
    }

    /** @return {@code true} once {@code needle} has appeared in the output. */
    public boolean sawLineContaining(final String needle) {
        return !containing(needle).isEmpty();
    }
}
