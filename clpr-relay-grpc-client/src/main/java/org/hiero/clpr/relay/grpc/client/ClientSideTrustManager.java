// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.client;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.hiero.clpr.relay.core.Certs;
import org.jspecify.annotations.Nullable;

/**
 * Client-side trust manager that authenticates the dialed server by validating that the leaf it
 * presents chains to a single expected CA — the {@code tls_certificate} published for that peer in the
 * on-chain roster.
 *
 * <p>What authenticates the peer is (a) the presented leaf is not a CA certificate, is within its
 * validity window, and its signature verifies against the expected CA's public key, and (b) the
 * TLS handshake proves the peer holds the leaf's private key. Hostname is deliberately not checked; the
 * network address travels in a separate roster field, not the certificate.
 */
final class ClientSideTrustManager extends X509ExtendedTrustManager {

    private final X509Certificate expectedCa;

    /**
     * @param expectedCa the peer's on-chain {@code tls_certificate} (its CA), already parsed
     */
    ClientSideTrustManager(final X509Certificate expectedCa) {
        this.expectedCa = expectedCa;
    }

    private void check(@Nullable final X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("server presented no certificate");
        }
        if (!Certs.chainsTo(chain[0], expectedCa)) {
            throw new CertificateException("server certificate does not chain to the peer's on-chain CA certificate");
        }
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    // The relay is the TLS client here; it never validates a peer's client certificate.
    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
        throw new CertificateException("client certificate validation is not supported on the client side");
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        throw new CertificateException("client certificate validation is not supported on the client side");
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("client certificate validation is not supported on the client side");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
