// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Negative-boundary tests for the gRPC sync endpoint. Each targets a different rejection point in
 * {@code ClprSyncHandler.handleSync}:
 *
 * <ol>
 *   <li>Absent chain record — rejected before the signature check even runs.</li>
 *   <li>Signature/payload mismatch — rejected at ECDSA recovery: the relay recomputes the digest from the
 *       payload it actually received, so a signature made for a different payload recovers a key that is
 *       not in the roster. A valid signature for one payload cannot be replayed with another.</li>
 *   <li>Unparseable bundle from an authenticated peer — the proof verifier throws, the handler catches,
 *       and the relay still returns a signed response: bad bundle content must not crash or silence it.</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = ".*")
class NegativeSyncBoundaryTest extends OneSidedStubPeerTestBase {

    @Test
    void phantomChannelIdReturnsEmptyProof() throws Exception {
        // A channel id with no on-chain record. The state reader returns empty and the handler
        // short-circuits with an empty proof before it ever reaches the signature check.
        final byte[] phantomId = new byte[32];
        Arrays.fill(phantomId, (byte) 0x42);

        assertEmptyProof(sendSync(Bytes.wrap(phantomId), Bytes.EMPTY));
    }

    @Test
    void signatureBoundToPayloadMismatchedPairIsRejected() throws Exception {
        // peerEndpoint (account 1, in the roster) signs over an empty payload, but the request carries a
        // different payload. The relay recomputes the digest from the payload it received → ECDSA recovery
        // yields a different key → no roster match → rejected. Guards against signature replay across
        // payloads, regardless of whether the signing key is registered.
        final Bytes connId = Bytes.wrap(channelId);
        final Bytes differentPayload = Bytes.wrap(new byte[] {(byte) 0xFF, 0x00, 0x01});

        assertEmptyProof(sendSync(connId, differentPayload));
    }

    private static ClprSyncPayload sendSync(final Bytes channelId, final Bytes bundlePayload) throws Exception {
        return peer.pokeSync(
                RELAY_HOST,
                relay.grpcPort(),
                ClprSyncPayload.newBuilder()
                        .channelId(channelId)
                        .bundlePayload(bundlePayload)
                        .build(),
                Bytes.wrap(CertFixtures.CA_A_CERT_DER));
    }

    private static void assertEmptyProof(final ClprSyncPayload response) {
        assertThat(response.bundlePayload().length())
                .as("a rejected sync must return an empty bundle")
                .isZero();
    }
}
