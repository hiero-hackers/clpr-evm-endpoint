// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;
import org.hiero.clpr.relay.core.testfixtures.CertFixtures;
import org.junit.jupiter.api.Test;

class LeafKeyManagerTest {
    final Metrics metrics = new SimpleMetrics();

    @Test
    void current_returnsInitialLeaf() throws Exception {
        final var km = new LeafKeyManager(CertFixtures.CA_A_KEY, Duration.ZERO, metrics);

        final var leaf = km.current();

        assertThat(leaf).isNotNull();
        assertThat(Certs.chainsTo(leaf.cert(), CertFixtures.CA_A_CERT)).isTrue();
    }

    @Test
    void getCertificateChain_returnsSingleLeafCert() throws Exception {
        final var km = new LeafKeyManager(CertFixtures.CA_A_KEY, Duration.ZERO, metrics);

        final var chain = km.getCertificateChain("clpr");

        assertThat(chain).hasSize(1);
        assertThat(chain[0]).isEqualTo(km.current().cert());
    }

    @Test
    void getPrivateKey_returnsLeafKey() throws Exception {
        final var km = new LeafKeyManager(CertFixtures.CA_A_KEY, Duration.ZERO, metrics);

        assertThat(km.getPrivateKey("clpr")).isEqualTo(km.current().key());
    }

    @Test
    void withZeroValidity_leafNeverRotates() throws Exception {
        final var km = new LeafKeyManager(CertFixtures.CA_A_KEY, Duration.ZERO, metrics);
        final var initial = km.current();

        // Trigger the check path multiple times; rotation must not fire.
        km.getCertificateChain("clpr");
        km.getCertificateChain("clpr");

        assertThat(km.current()).isSameAs(initial);
    }

    @Test
    void withExpiredValidity_leafRotatesOnNextHandshake() throws Exception {
        // 1 ns validity — already past due immediately after construction.
        final var km = new LeafKeyManager(CertFixtures.CA_A_KEY, Duration.ofNanos(1), metrics);
        final var initial = km.current();

        // A tiny sleep ensures rotateDue is in the past before the handshake check.
        Thread.sleep(1);
        km.getCertificateChain("clpr");

        assertThat(km.current()).isNotSameAs(initial);
        assertThat(Certs.chainsTo(km.current().cert(), CertFixtures.CA_A_CERT)).isTrue();
    }
}
