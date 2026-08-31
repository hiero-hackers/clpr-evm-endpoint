// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.AbstractLogHandler;
import com.swirlds.logging.api.internal.format.FormattedLinePrinter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;

/**
 * {@link com.swirlds.logging.api.extensions.handler.LogHandler} that writes each event to
 * {@link System#out} and flushes immediately.
 *
 * <p>The default {@code console} handler shipped with {@code swirlds-logging} wraps stdout in
 * an 8 KB buffer and relies on a 1 s periodic flush; under Gradle's stdout capture that delay
 * is enough to make startup logs look like they never happened, and to truncate the tail of the
 * output when a forked test JVM exits before the next flush. This handler skips the buffer so
 * messages appear as they are emitted.
 *
 * <p>Lives in {@code clpr-relay-core} so it is reachable from every module's test JVM (each one
 * depends on {@code core}), letting {@code log.test.properties} select it for all unit and
 * integration tests.
 *
 * <p>Selected via {@code logging.handler.<name>.type=console-immediate} in the active logging
 * config ({@code log.properties} at runtime, {@code log.test.properties} under test). Registered
 * as a {@link com.swirlds.logging.api.extensions.handler.LogHandlerFactory} via
 * {@link ImmediateConsoleHandlerFactory} (see {@code module-info.java}).
 */
public final class ImmediateConsoleHandler extends AbstractLogHandler {

    /** Type name used in {@code logging.handler.<name>.type}. */
    public static final String TYPE_NAME = "console-immediate";

    private final FormattedLinePrinter printer;
    private final PrintStream out = System.out;

    public ImmediateConsoleHandler(@NonNull final String handlerName, @NonNull final Configuration configuration) {
        super(handlerName, configuration);
        this.printer = FormattedLinePrinter.createForHandler(handlerName, configuration);
    }

    @Override
    public void handle(@NonNull final LogEvent event) {
        final StringBuilder sb = new StringBuilder(256);
        printer.print(sb, event);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8), 0, sb.length());
        out.flush();
    }

    @Override
    public void stopAndFinalize() {
        out.flush();
    }
}
