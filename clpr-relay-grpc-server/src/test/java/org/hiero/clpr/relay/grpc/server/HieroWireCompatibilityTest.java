// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.grpc.server;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Wire-format compatibility check between this repo's PBJ types and clpr-hiero's PBJ types
 * for {@link com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration}.
 *
 * <p>Currently disabled: the previous fixture pinned the now-removed
 * {@code initial_trust_anchor} (tag 7) and {@code initial_trust_anchor_id} (tag 8) fields,
 * which were dropped to align this repo's schema with the platform-neutral 6-field
 * {@code ClprLedgerConfiguration} from {@code clpr-service-spec.md}. The QBFT trust anchor
 * has been moved into the EVM-specific
 * {@link com.hedera.hapi.node.state.clpr.QbftLedgerConfigurationPayload} wrapper.
 *
 * <p>Re-enable once the parallel clpr-hiero change lands. Regenerate the fixture from the
 * updated clpr-hiero PBJ output and re-pin assertions covering all 6 retained fields.
 */
@Disabled("Fixture pinned removed fields 7/8; awaiting clpr-hiero counterpart and regenerated fixture")
class HieroWireCompatibilityTest {

    @Test
    void parsesHieroEncodedLedgerConfiguration() {
        // Intentionally empty: the test is @Disabled until the fixture is regenerated against
        // the clpr-hiero counterpart that removes ClprLedgerConfiguration fields 7+8 and moves
        // the trust anchor into the new QBFT-specific wrapper payload.
    }
}
