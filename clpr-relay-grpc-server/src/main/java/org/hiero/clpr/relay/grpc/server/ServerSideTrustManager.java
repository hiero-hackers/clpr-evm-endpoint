// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.hiero.clpr.relay.core.PeerEndpointTlsRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Server-side trust check for the mandatory-mTLS sync listener. A client is accepted only if the
 * certificate it presents was signed by the CA of some endpoint currently in the on-chain roster.
 *
 * <p>The registry is queried on every handshake, so roster additions and CA rotations take effect
 * without rebuilding the {@code SSLContext}. This only confirms the dialer is a registered endpoint;
 * per-channel authorization is checked later, at a higher layer.
 *
 * <p>Trust is by CA signature verification: the leaf must not be a CA certificate, must be within its
 * validity window, and its signature must verify against a roster CA's public key. Hostnames
 * are not checked — identity comes from the CA chain, not the hostname. A malformed on-chain certificate
 * cannot match and is skipped rather than failing the handshake.
 */
final class ServerSideTrustManager extends X509ExtendedTrustManager {

    private final PeerEndpointTlsRegistry registry;

    /**
     * @param registry global registry of peer TLS CA certificates; queried on each handshake
     */
    ServerSideTrustManager(final PeerEndpointTlsRegistry registry) {
        this.registry = registry;
    }

    private void check(@Nullable final X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("client presented no certificate");
        }
        if (registry.matchByCa(chain[0]).isEmpty()) {
            throw new CertificateException("client certificate does not chain to any known peer roster CA");
        }
    }

    @Override
    public void checkClientTrusted(@Nullable final X509Certificate[] chain, final String authType)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(@Nullable final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(
            @Nullable final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    // The secure listener runs clientAuth=REQUIRE; the relay never acts as the TLS client on this path.
    @Override
    public void checkServerTrusted(@Nullable final X509Certificate[] chain, final String authType)
            throws CertificateException {
        throw new CertificateException("server certificate validation is not supported on the server side");
    }

    @Override
    public void checkServerTrusted(@Nullable final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        throw new CertificateException("server certificate validation is not supported on the server side");
    }

    @Override
    public void checkServerTrusted(
            @Nullable final X509Certificate[] chain, final String authType, final SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("server certificate validation is not supported on the server side");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
