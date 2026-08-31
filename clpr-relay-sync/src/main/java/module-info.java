// SPDX-License-Identifier: Apache-2.0
module org.hiero.clpr.relay.sync {
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.clpr.relay.core;
    requires transitive org.hiero.clpr.relay.grpc.client;
    requires transitive org.hiero.clpr.relay.proto;
    requires com.swirlds.logging;
    requires static org.jspecify;

    exports org.hiero.clpr.relay.sync;
}
