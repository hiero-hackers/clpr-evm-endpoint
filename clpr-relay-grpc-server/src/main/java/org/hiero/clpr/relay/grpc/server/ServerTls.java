// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.hiero.clpr.relay.core.LeafKeyManager;

/**
 * Builds the server-side {@link SSLContext} for the secure sync listener.
 *
 * <p>The supplied {@link LeafKeyManager} is passed directly to {@link SSLContext#init}, so the JDK TLS
 * stack calls it on every new handshake. Because {@link LeafKeyManager} holds the active leaf in an
 * {@link java.util.concurrent.atomic.AtomicReference}, it serves the most-recent leaf without requiring
 * the {@code SSLContext} to be rebuilt on rotation.
 */
final class ServerTls {

    private ServerTls() {}

    /**
     * Build a mutual-TLS {@link SSLContext} that presents this endpoint's leaf via {@code keyManager}
     * and validates the dialer's client certificate with {@code clientTrustManager}.
     *
     * @param keyManager          the dynamic key manager supplying this endpoint's leaf on each handshake
     * @param clientTrustManager  the trust manager that validates the dialer's client leaf against the
     *                            on-chain roster CA chain
     * @return a configured {@code SSLContext} ready for the mandatory-mTLS sync listener
     * @throws IllegalStateException if the context cannot be constructed
     */
    static SSLContext serverContext(final LeafKeyManager keyManager, final TrustManager clientTrustManager) {
        try {
            final SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[] {keyManager}, new TrustManager[] {clientTrustManager}, new SecureRandom());
            return ctx;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("failed to build the mTLS SSLContext: " + e.getMessage(), e);
        }
    }
}
