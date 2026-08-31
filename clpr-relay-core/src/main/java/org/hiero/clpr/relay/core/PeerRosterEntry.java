// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import org.jspecify.annotations.Nullable;

/**
 * A peer endpoint together with its parsed TLS certificate. The certificate is parsed once when the
 * roster is installed in {@link PeerEndpointCache}, so callers that need to validate a leaf against
 * the roster (e.g. at every mTLS handshake) never pay the DER parsing cost per call.
 *
 * @param endpoint the on-chain peer endpoint (authoritative roster state)
 * @param tlsCert  the parsed form of {@link ClprEndpoint#tlsCertificate()}, or {@code null} when
 *                 the endpoint published no certificate or its certificate bytes are malformed
 */
public record PeerRosterEntry(
        ClprEndpoint endpoint, @Nullable X509Certificate tlsCert) {

    /**
     * Parse the on-chain TLS certificate of {@code endpoint} and return the combined entry. A missing,
     * empty, or malformed certificate produces an entry with {@code tlsCert == null} — such an entry
     * can never satisfy a certificate-chain check and is safely skipped.
     *
     * @param endpoint the peer endpoint to wrap
     * @return the entry with the certificate pre-parsed (or {@code null} if none / malformed)
     */
    public static PeerRosterEntry parse(final ClprEndpoint endpoint) {
        final Bytes certBytes = endpoint.tlsCertificate();
        if (certBytes == null || certBytes.length() == 0) {
            return new PeerRosterEntry(endpoint, null);
        }
        try {
            return new PeerRosterEntry(endpoint, Certs.parse(certBytes.toByteArray()));
        } catch (final CertificateException malformed) {
            return new PeerRosterEntry(endpoint, null);
        }
    }
}
