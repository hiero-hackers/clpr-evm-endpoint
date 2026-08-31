// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the full {@link BidirectionalMessageTest} scenarios over the <b>secure</b> (mandatory-mTLS)
 * transport: {@link SecureTransport} makes {@link IntegrationTestBase} provision both relays with
 * mTLS-only sync listeners and publish each relay's certificate in the peer's on-chain roster.
 *
 * <p>Inheriting the message-flow assertions unchanged is the point: a message arriving on each side can
 * only happen if the mutual-TLS handshake completed in that direction (dialer pins the peer's on-chain
 * server cert; acceptor validates the dialer's client cert against its roster). A plaintext dial to
 * these listeners would fail the handshake, so green here == mTLS working both ways.
 */
@SecureTransport
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class SecureBidirectionalMessageTest extends BidirectionalMessageTest {}
