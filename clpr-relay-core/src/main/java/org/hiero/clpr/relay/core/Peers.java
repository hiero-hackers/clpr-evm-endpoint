// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;

public class Peers {
    /**
     * Returns a human-readable label for {@code peer}, suitable for logging and metric tags. Prefers
     * {@code "host:port"} when a service endpoint is known; falls back to {@code "unknown"}.
     */
    public static String peerLabel(final ClprEndpoint peer) {
        final var ep = peer.serviceEndpoint();
        if (ep != null && !ep.ipAddress().isBlank()) {
            return ep.ipAddress() + ":" + ep.port();
        }
        return "unknown";
    }
}
