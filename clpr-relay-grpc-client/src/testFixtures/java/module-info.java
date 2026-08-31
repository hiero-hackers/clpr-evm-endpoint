// SPDX-License-Identifier: Apache-2.0
module org.hiero.clpr.relay.grpc.client.test.fixtures {
    requires transitive org.hiero.clpr.relay.proto;
    requires com.swirlds.logging;
    requires io.grpc.netty.shaded;
    requires io.grpc.stub;
    requires io.grpc;

    exports org.hiero.clpr.relay.grpc.client.testfixtures;
}
