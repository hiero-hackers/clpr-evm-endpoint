// SPDX-License-Identifier: Apache-2.0
module org.hiero.clpr.relay.integration.tests {
    requires transitive org.hiero.clpr.relay.evm;
    requires transitive org.hiero.clpr.relay.proto;
    requires transitive org.hiero.clpr.relay.test.harness;
    requires transitive com.fasterxml.jackson.databind;
    requires transitive org.testcontainers;
    requires com.hedera.pbj.runtime;
    requires com.github.dockerjava.api;
    requires static transitive org.jspecify;

    exports org.hiero.clpr.relay.integration;

    // Allow the Testcontainers JUnit Jupiter extension to reflect on @Container fields.
    opens org.hiero.clpr.relay.integration to
            org.testcontainers.junit.jupiter;
}
