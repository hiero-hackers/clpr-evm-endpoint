// SPDX-License-Identifier: Apache-2.0
module org.hiero.clpr.relay.grpc.client {
    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.clpr.relay.core;
    requires transitive org.hiero.clpr.relay.proto;
    requires com.swirlds.logging;
    requires io.grpc.netty.shaded;
    requires io.grpc.stub;
    requires io.grpc;
    requires static org.jspecify;

    exports org.hiero.clpr.relay.grpc.client;
}
