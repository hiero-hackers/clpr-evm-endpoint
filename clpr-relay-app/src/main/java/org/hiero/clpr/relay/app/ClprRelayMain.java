// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.swirlds.metrics.api.Metrics;
import org.hiero.clpr.relay.core.metrics.SimpleMetrics;

/**
 * Application entry point for the CLPR EVM Relay.
 *
 * <p>The heavy lifting of wiring components lives in {@link RelayInstance#build(RelayConfig, Metrics)};
 * this class only loads configuration, starts the instance, and installs a JVM shutdown hook.
 */
public class ClprRelayMain {

    /**
     * System property naming the TCP port for the Prometheus {@code /metrics} endpoint.
     * Default {@code 9547}; set to {@code 0} to let the OS assign a port; set to a negative
     * number to disable the endpoint entirely.
     */
    static final String METRICS_PORT_PROPERTY = "relay.metrics.port";

    static final int DEFAULT_METRICS_PORT = 9547;

    /**
     * Application entry point.
     *
     */
    static void main() {
        final var config = loadConfig();
        final Metrics metrics = new SimpleMetrics();
        final var relay = RelayInstance.build(config, metrics);

        final MetricsHttpServer metricsServer = startMetricsServer(metrics);

        relay.start();
        System.out.println("CLPR EVM Relay started on port " + relay.grpcPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down CLPR EVM Relay...");
            relay.stop();
            if (metricsServer != null) {
                metricsServer.stop();
            }
        }));

        // Block main thread
        try {
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Load application configuration via the swirlds-config framework.
     *
     * <p>Config file resolution order (first match wins):
     * <ol>
     *   <li>System property {@code relay.configFile}</li>
     *   <li>Environment variable {@code RELAY_CONFIG_FILE}</li>
     *   <li>{@code relay.yaml} in the current working directory (auto-fallback)</li>
     * </ol>
     *
     * <p>If a YAML file is found it is parsed and its values are used as the mid-priority source.
     * Individual system properties always win over file values; both win over the built-in
     * defaults listed below.
     *
     * <p>Only the scalar {@code grpc} and {@code backoff} blocks are system-property overridable; the
     * {@code localNetworks}, {@code clprServices}, and {@code peerProofTypes} sections are set from
     * the YAML file only. Signing keys are configured per {@code ClprService} deployment
     * ({@code clprServices[].defaultSigningPrivateKeyHex} plus per-channel overrides), not by a
     * single top-level property.
     *
     * <p>Recognized system properties:
     * <ul>
     *   <li>{@code relay.configFile} — explicit path to a YAML config file</li>
     *   <li>{@code relay.grpc.port} — gRPC server port (default: {@code 9545})</li>
     *   <li>{@code relay.grpc.maxMessageSize} — max gRPC message size in bytes
     *       (default: {@code 1048576})</li>
     *   <li>{@code relay.backoff.baseMs} — first-failure backoff in ms (default: {@code 1000})</li>
     *   <li>{@code relay.backoff.capMs} — backoff ceiling in ms (default: {@code 30000})</li>
     *   <li>{@code relay.metrics.port} — Prometheus / healthz HTTP port (default: {@code 9547})</li>
     * </ul>
     *
     * @return the loaded {@link RelayConfig}
     * @throws IllegalStateException if a config file is configured but cannot be read or parsed
     */
    private static RelayConfig loadConfig() {
        return RelayConfigLoader.load();
    }

    /**
     * Start the Prometheus metrics endpoint, honoring {@link #METRICS_PORT_PROPERTY}.
     * Returns {@code null} if the property is set to a negative value (endpoint disabled).
     */
    private static MetricsHttpServer startMetricsServer(final Metrics metrics) {
        final int port;
        final String raw = System.getProperty(METRICS_PORT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            port = DEFAULT_METRICS_PORT;
        } else {
            try {
                port = Integer.parseInt(raw.trim());
            } catch (final NumberFormatException e) {
                throw new IllegalStateException("Invalid " + METRICS_PORT_PROPERTY + ": " + raw, e);
            }
        }
        if (port < 0) {
            return null;
        }
        final var server = new MetricsHttpServer(port, metrics);
        server.start();
        return server;
    }
}
