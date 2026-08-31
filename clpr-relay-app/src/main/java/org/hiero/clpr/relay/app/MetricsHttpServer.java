// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.app;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.metrics.api.Metrics;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import org.hiero.clpr.relay.core.metrics.PrometheusExporter;
import org.jspecify.annotations.NonNull;

/**
 * Serves the {@code /metrics} Prometheus scrape endpoint on a dedicated HTTP port.
 *
 * <p>Rendering is delegated to {@link PrometheusExporter} so the server itself stays
 * trivial; Helidon is reused (already a relay dependency for gRPC) to avoid adding a
 * second HTTP stack just for this endpoint.
 */
public final class MetricsHttpServer {

    private static final Logger LOGGER = Loggers.getLogger(MetricsHttpServer.class);

    private final int port;
    private final Metrics metrics;
    private volatile WebServer server;

    public MetricsHttpServer(final int port, @NonNull final Metrics metrics) {
        this.port = port;
        this.metrics = metrics;
    }

    public void start() {
        final HttpRouting.Builder routing = HttpRouting.builder()
                .get("/metrics", (req, res) -> {
                    res.headers().contentType(io.helidon.http.HttpMediaType.create(PrometheusExporter.CONTENT_TYPE));
                    res.send(PrometheusExporter.render(metrics));
                })
                .get("/healthz", (req, res) -> res.send("ok\n"));
        server = WebServer.builder().port(port).addRouting(routing).build().start();
        LOGGER.info("Metrics HTTP endpoint listening on :{}/metrics", server.port());
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    public int port() {
        return server != null ? server.port() : -1;
    }
}
