// SPDX-License-Identifier: Apache-2.0
module org.hiero.clpr.relay.app {
    requires transitive org.hiero.clpr.relay.core;
    requires transitive org.hiero.clpr.relay.evm;
    requires transitive org.hiero.clpr.relay.sync;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.metrics.api;
    requires org.hiero.clpr.relay.grpc.client;
    requires org.hiero.clpr.relay.grpc.server;
    requires org.hiero.clpr.relay.proto;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;
    requires io.helidon.common.media.type;
    requires io.helidon.common;
    requires io.helidon.http;
    requires io.helidon.webserver;
    requires static org.jspecify;

    exports org.hiero.clpr.relay.app;
}
