// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e;

import java.util.EnumSet;
import java.util.Set;

/**
 * Sizing and capability profile for an E2E run, selected by the {@code clpr.e2e.profile} system
 * property ({@code local} by default, {@code ci} on the PR runner).
 *
 * <p>This is the <b>only</b> place run sizing is decided. Every default that costs memory or time
 * — node counts, container heap caps, poll cadence — is read from here, so retargeting the suite at
 * a different runner is a data change. Tests that need exact numbers set them explicitly on the
 * spec records and ignore the profile.
 */
public enum E2EProfile {

    /**
     * Developer machine: generous heaps and no capability restrictions. Assumes Docker has several
     * GB and more than four cores available.
     */
    LOCAL("local", 1024, 512, EnumSet.allOf(Capability.class)),

    /**
     * GitHub-hosted runner (4 vCPU / 16 GB). Heap caps are mandatory here, not advisory: six
     * containers fit comfortably only because Besu is pinned to 512 MB and each endpoint to 256 MB.
     * {@link Capability#HIGH_LOAD} and {@link Capability#MULTI_VALIDATOR_QBFT} are withheld, so the
     * load suite and the multi-validator experiments skip instead of thrashing.
     */
    CI("ci", 512, 256, EnumSet.of(Capability.NETWORK_PARTITION, Capability.DESTRUCTIVE));

    private final String propertyValue;
    private final int besuHeapMb;
    private final int endpointHeapMb;
    private final Set<Capability> capabilities;

    E2EProfile(
            final String propertyValue,
            final int besuHeapMb,
            final int endpointHeapMb,
            final Set<Capability> capabilities) {
        this.propertyValue = propertyValue;
        this.besuHeapMb = besuHeapMb;
        this.endpointHeapMb = endpointHeapMb;
        this.capabilities = Set.copyOf(capabilities);
    }

    /** System property selecting the profile. */
    public static final String PROFILE_PROPERTY = "clpr.e2e.profile";

    /**
     * Resolve the active profile from {@link #PROFILE_PROPERTY}, defaulting to {@link #LOCAL}.
     *
     * @return the active profile
     * @throws IllegalArgumentException if the property names an unknown profile
     */
    public static E2EProfile active() {
        final String value = System.getProperty(PROFILE_PROPERTY, LOCAL.propertyValue);
        for (final E2EProfile profile : values()) {
            if (profile.propertyValue.equalsIgnoreCase(value)) {
                return profile;
            }
        }
        throw new IllegalArgumentException(
                "Unknown " + PROFILE_PROPERTY + ": '" + value + "'. Expected one of: local, ci");
    }

    /** @return {@code -Xmx} value in MB for each Besu container. */
    public int besuHeapMb() {
        return besuHeapMb;
    }

    /** @return {@code -Xmx} value in MB for each endpoint container. */
    public int endpointHeapMb() {
        return endpointHeapMb;
    }

    /** @return {@code true} if this profile offers every capability in {@code required}. */
    public boolean supports(final Capability... required) {
        for (final Capability capability : required) {
            if (!capabilities.contains(capability)) {
                return false;
            }
        }
        return true;
    }

    /** @return the offered capabilities. */
    public Set<Capability> capabilities() {
        return capabilities;
    }
}
