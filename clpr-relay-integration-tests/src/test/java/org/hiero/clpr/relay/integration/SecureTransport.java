// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link IntegrationTestBase} subclass to provision its two relays over the <b>secure</b>
 * (mandatory-mTLS) transport instead of plaintext. Absence of this annotation means plaintext — the
 * historical default that every existing subclass relies on.
 *
 * <p>Read reflectively from the concrete test class in {@code IntegrationTestBase}'s {@code @BeforeAll}
 * (via injected {@code TestInfo}), because a static lifecycle method cannot be overridden. When the
 * plaintext transport is eventually retired, this annotation and the branch it drives disappear and the
 * base provisions secure unconditionally.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface SecureTransport {}
