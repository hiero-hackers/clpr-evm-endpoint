// SPDX-License-Identifier: Apache-2.0
module org.hiero.clpr.relay.proto {
    requires transitive com.hedera.pbj.runtime;
    requires org.antlr.antlr4.runtime;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive org.jspecify;
    requires static java.annotation;

    exports com.hedera.hapi.node.base;
    exports com.hedera.hapi.node.base.codec;
    exports com.hedera.hapi.node.base.schema;
    exports com.hedera.hapi.node.clpr;
    exports com.hedera.hapi.node.clpr.codec;
    exports com.hedera.hapi.node.clpr.schema;
    exports com.hedera.hapi.node.state.clpr;
    exports com.hedera.hapi.node.state.clpr.codec;
    exports com.hedera.hapi.node.state.clpr.schema;
}
