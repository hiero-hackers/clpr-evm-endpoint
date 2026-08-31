// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a method as a black-box E2E test of the {@code clpr-evm-endpoint}.
 *
 * <p>An annotated method may declare a single {@link E2EEnvironment} parameter; the
 * {@link E2ETestExtension} builds one per test method and closes it afterwards, so every test gets
 * fresh Besu networks and fresh endpoint containers. That isolation is not a nicety — a channel
 * whose backlog exceeds the relay's bundle batch wedges irrecoverably (see
 * {@link Capability#DESTRUCTIVE}), so sharing an environment across tests would let one failure
 * poison every later test.
 *
 * <p>Tests are skipped unless {@code RUN_E2E_TESTS} is set in the environment and Docker plus a
 * built {@code clpr-smart-contracts} checkout are available. Run them with:
 *
 * <pre>{@code
 * RUN_E2E_TESTS=1 ./gradlew :clpr-relay-e2e-tests:test
 * }</pre>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith(E2ETestExtension.class)
public @interface E2ETest {

    /**
     * Wall-clock budget for the whole test method. On expiry the extension interrupts the test and
     * dumps diagnostics, so a hung environment fails with evidence rather than with the build's
     * global timeout.
     *
     * @return the budget in seconds
     */
    long timeoutSeconds() default 900L;

    /**
     * Capabilities this test needs. A test requiring a capability the active {@link E2EProfile}
     * does not offer is reported as disabled rather than failed, which is how the load-heavy and
     * environment-destroying tests stay out of the PR pipeline.
     *
     * @return the required capabilities
     */
    Capability[] requires() default {};
}
