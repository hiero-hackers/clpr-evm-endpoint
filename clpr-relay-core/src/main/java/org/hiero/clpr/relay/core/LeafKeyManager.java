// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.core;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Counter.Config;
import com.swirlds.metrics.api.Metrics;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * A TLS key manager for this endpoint's in-memory leaf certificate that re-mints the leaf on demand
 * when the rotation window has elapsed.
 *
 * <p>The active leaf and its rotation deadline are stored together in an {@link AtomicReference}.
 * On each TLS handshake the JDK TLS stack calls {@link #getCertificateChain}/{@link #getPrivateKey},
 * which check whether the deadline has passed and, if so, attempt a rotation via a compare-and-set.
 * A concurrent handshake that loses the CAS simply returns the winner's newly-installed leaf — the
 * wasted key-generation work is harmless. The new deadline is stamped after key generation completes,
 * so the effective rotation period is {@code validity + key_generation_time} (typically negligible).
 *
 * <p>When {@code validity} is {@link Duration#ZERO} rotation is disabled: the initial leaf is valid
 * for 10 years and the deadline is set to {@link Instant#MAX}.
 */
public final class LeafKeyManager extends X509ExtendedKeyManager {

    private static final Logger log = Loggers.getLogger(LeafKeyManager.class);

    /** Alias returned to the JDK TLS stack for every key-selection query. */
    private static final String ALIAS = "clpr";

    /** Validity window used when rotation is disabled ({@code validity == Duration.ZERO}). */
    private static final Duration DISABLED_VALIDITY = Duration.ofDays(3650);

    private final Counter leafRotationCounter;
    private final Counter leafRotationErrorCounter;

    private record State(LeafCertificate leaf, Instant rotateDue) {}

    private final PrivateKey caKey;
    private final Duration validity;
    private final AtomicReference<State> state;

    private static final Config ROTATIONS =
            new Counter.Config("mtls", "leaf_rotations").withDescription("mtls leaf certificate rotations");
    private static final Config ROTATION_ERRORS = new Counter.Config("mtls", "leaf_rotations_errors")
            .withDescription("mtls leaf certificate rotations errors");
    /**
     * Creates a key manager and mints the initial leaf immediately.
     *
     * <p>When {@code validity} is {@link Duration#ZERO}, rotation is disabled: the initial leaf is
     * valid for {@link #DISABLED_VALIDITY} and {@link #getCertificateChain}/{@link #getPrivateKey} always return the
     * same instance.
     *
     * @param caKey    the endpoint's CA private key (ECDSA P-384); used to sign each minted leaf
     * @param validity how long between re-mints; {@link Duration#ZERO} disables rotation
     * @param metrics  metrics registry
     * @throws GeneralSecurityException if the initial leaf cannot be generated
     */
    public LeafKeyManager(final PrivateKey caKey, final Duration validity, final Metrics metrics)
            throws GeneralSecurityException {
        this.caKey = caKey;
        this.validity = validity;
        final boolean disabled = validity.isZero();
        final LeafCertificate initial = LeafCertificate.generate(caKey, disabled ? DISABLED_VALIDITY : validity);
        final Instant rotateDue = disabled ? Instant.MAX : Instant.now().plus(validity);
        this.state = new AtomicReference<>(new State(initial, rotateDue));
        this.leafRotationCounter = metrics.getOrCreate(ROTATIONS);
        this.leafRotationErrorCounter = metrics.getOrCreate(ROTATION_ERRORS);
    }

    /**
     * Returns the currently-active leaf certificate and key.
     *
     * @return the current leaf; never {@code null}
     */
    public LeafCertificate current() {
        return state.get().leaf();
    }

    private LeafCertificate currentLeaf() {
        final State s = state.get();
        if (!validity.isZero() && Instant.now().isAfter(s.rotateDue())) {
            tryRotate(s);
        }
        return state.get().leaf();
    }

    private void tryRotate(final State expected) {
        try {
            final LeafCertificate fresh = LeafCertificate.generate(caKey, validity);
            if (state.compareAndSet(expected, new State(fresh, Instant.now().plus(validity)))) {
                leafRotationCounter.increment();
                log.info("mTLS leaf certificate rotated; next rotation in {} s", validity.toSeconds());
            }
        } catch (final GeneralSecurityException e) {
            leafRotationErrorCounter.increment();
            log.warn("failed to rotate mTLS leaf certificate; keeping current leaf: {}", e.getMessage());
        }
    }

    @Override
    public String[] getServerAliases(final String keyType, final Principal[] issuers) {
        return new String[] {ALIAS};
    }

    @Override
    public String chooseServerAlias(final String keyType, final Principal[] issuers, final Socket socket) {
        return ALIAS;
    }

    @Override
    public String chooseEngineServerAlias(final String keyType, final Principal[] issuers, final SSLEngine engine) {
        return ALIAS;
    }

    @Override
    public String[] getClientAliases(final String keyType, final Principal[] issuers) {
        return new String[] {ALIAS};
    }

    @Override
    public String chooseClientAlias(final String[] keyTypes, final Principal[] issuers, final Socket socket) {
        return ALIAS;
    }

    @Override
    public String chooseEngineClientAlias(final String[] keyTypes, final Principal[] issuers, final SSLEngine engine) {
        return ALIAS;
    }

    @Override
    public X509Certificate[] getCertificateChain(final String alias) {
        return new X509Certificate[] {currentLeaf().cert()};
    }

    @Override
    public PrivateKey getPrivateKey(final String alias) {
        return currentLeaf().key();
    }
}
