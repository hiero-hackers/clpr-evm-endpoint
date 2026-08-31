// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.e2e.endpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.hiero.clpr.relay.e2e.chain.Accounts;
import org.hiero.clpr.relay.e2e.chain.TestAccount;
import org.hiero.clpr.relay.evm.EthSigner;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The endpoint containers declared by one test.
 *
 * <p>Endpoints occupy the low host indices of the environment's /24 and keep their address across
 * restarts, because a channel's on-chain peer roster stores that address verbatim.
 */
public final class EvmEndpoints {

    /** System property naming the staged Docker build context, set by the Gradle test task. */
    public static final String IMAGE_DIR_PROPERTY = "clpr.e2e.imageDir";

    /** First host index for endpoints; chains start at 40 (see the chain layer). */
    private static final int ENDPOINT_HOST_BASE = 0;

    private final Accounts accounts;
    private final Network dockerNetwork;
    private final IntFunction<String> hostAddresses;
    private final int heapMb;
    private final ImageFromDockerfile image;

    private final List<EvmEndpoint> endpoints = new ArrayList<>();

    public EvmEndpoints(
            final Accounts accounts,
            final Network dockerNetwork,
            final IntFunction<String> hostAddresses,
            final int heapMb) {
        this.accounts = accounts;
        this.dockerNetwork = dockerNetwork;
        this.hostAddresses = hostAddresses;
        this.heapMb = heapMb;
        this.image = buildImage();
    }

    /**
     * The endpoint image, built once per {@code EvmEndpoints} instance from the staged context.
     *
     * <p>The context is produced on the host by the {@code stageEndpointImage} Gradle task and holds
     * the relay's real runtime jars plus the production entrypoint, so the image under test is the
     * shipped artifact. The production {@code Dockerfile} is deliberately not used: it runs a full
     * Gradle build inside Docker with a cold cache, which would add minutes to every test.
     */
    private static ImageFromDockerfile buildImage() {
        final String dir = System.getProperty(IMAGE_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException(IMAGE_DIR_PROPERTY
                    + " is not set — run the E2E suite through Gradle so stageEndpointImage populates the"
                    + " Docker build context");
        }
        final Path context = Path.of(dir);
        if (!Files.isDirectory(context) || !Files.isRegularFile(context.resolve("Dockerfile"))) {
            throw new IllegalStateException("no staged endpoint image context at " + context.toAbsolutePath()
                    + " — expected a Dockerfile and a lib/ directory (run :clpr-relay-e2e-tests:stageEndpointImage)");
        }
        // withDockerfile() already registers the Dockerfile's PARENT DIRECTORY as the build context,
        // recursively. Adding the same files again with withFileFromPath() duplicates the ~400 MB
        // context and deadlocks: Testcontainers streams it through a PipedOutputStream whose reader
        // stops consuming, and the tarring thread blocks forever in awaitSpace() with no timeout and
        // no error. One call is both sufficient and necessary.
        return new ImageFromDockerfile("clpr-evm-endpoint-e2e:latest", false)
                .withDockerfile(context.resolve("Dockerfile"));
    }

    /**
     * Declare an endpoint.
     *
     * @param spec the endpoint description; the id is auto-numbered when left at the
     *     {@link EvmEndpointSpec#defaults()} value
     * @return the declared endpoint
     */
    public EvmEndpoint add(final EvmEndpointSpec spec) {
        final int index = endpoints.size();
        EvmEndpointSpec effective = spec;
        if ("endpoint".equals(spec.id())) {
            effective = effective.withId("endpoint-" + index);
        }
        assertIdUnique(effective.id());

        // Each endpoint gets its own signing account. The relay serialises submissions per signing
        // account, so two endpoints sharing a key would interleave nonces on one address and fail in
        // a way that mimics a relay defect. A test may pin a key deliberately (to force exactly that
        // collision, for instance); then no pool slot is consumed.
        final TestAccount signer = signerFor(effective);

        final EvmEndpoint endpoint = new EvmEndpoint(
                effective, signer, hostAddresses.apply(ENDPOINT_HOST_BASE + index), dockerNetwork, image, heapMb);
        endpoints.add(endpoint);
        return endpoint;
    }

    /**
     * Declare {@code count} endpoints with default settings.
     *
     * @param count how many
     * @return the declared endpoints
     */
    public List<EvmEndpoint> add(final int count) {
        final List<EvmEndpoint> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(add(EvmEndpointSpec.defaults()));
        }
        return out;
    }

    /** @return every declared endpoint, in declaration order. */
    public List<EvmEndpoint> all() {
        return List.copyOf(endpoints);
    }

    /**
     * @param index declaration order index
     * @return that endpoint
     */
    public EvmEndpoint get(final int index) {
        return endpoints.get(index);
    }

    /**
     * @param id the endpoint id
     * @return that endpoint
     */
    public EvmEndpoint byId(final String id) {
        return endpoints.stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no endpoint with id '" + id + "'; declared: "
                        + endpoints.stream().map(EvmEndpoint::id).toList()));
    }

    /** Start every declared endpoint. */
    public void startAll() {
        endpoints.forEach(EvmEndpoint::start);
    }

    /** Stop every endpoint, best-effort. */
    public void stopAll() {
        for (int i = endpoints.size() - 1; i >= 0; i--) {
            try {
                endpoints.get(i).stop();
            } catch (final RuntimeException e) {
                // Teardown is best-effort; one dead container must not strand the rest.
            }
        }
    }

    /**
     * Stop every endpoint and release its HTTP client. Called once from the environment's teardown —
     * distinct from {@link #stopAll()}, which a test may use mid-run and then restart from.
     */
    public void shutdown() {
        stopAll();
        for (final EvmEndpoint endpoint : endpoints) {
            try {
                endpoint.closeHttpClient();
            } catch (final RuntimeException e) {
                // best-effort
            }
        }
    }

    /** Restart every endpoint, one at a time. */
    public void restartAll() {
        endpoints.forEach(EvmEndpoint::restart);
    }

    /**
     * Restart endpoints one by one, pausing in between.
     *
     * <p>Rolling rather than simultaneous so the channel always has at least one live side, which is
     * what makes this a "does it resume correctly" test rather than a "does it survive a full
     * outage" test.
     *
     * @param between delay after each restart
     */
    public void rollingRestart(final Duration between) {
        for (final EvmEndpoint endpoint : endpoints) {
            endpoint.restart();
            try {
                Thread.sleep(between.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during rolling restart", e);
            }
        }
    }

    private TestAccount signerFor(final EvmEndpointSpec spec) {
        final String pinned = spec.signingKeyHex();
        if (pinned == null) {
            return accounts.allocate(spec.id() + " signer");
        }
        return new TestAccount(
                -1,
                pinned,
                new EthSigner(pinned).address(),
                EthSigner.derivePublicKey(pinned),
                spec.id() + " signer (key pinned by test)");
    }

    private void assertIdUnique(final String id) {
        if (endpoints.stream().anyMatch(e -> e.id().equals(id))) {
            throw new IllegalArgumentException("endpoint id '" + id + "' is already declared");
        }
    }
}
