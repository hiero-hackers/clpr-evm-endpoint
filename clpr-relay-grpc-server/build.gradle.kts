// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "CLPR EVM Relay — gRPC server (PBJ/Helidon)"

dependencies {
    api(project(":clpr-relay-proto"))
    api(project(":clpr-relay-core"))
    api("com.hedera.pbj:pbj-runtime")
    implementation("com.hedera.pbj:pbj-grpc-helidon")
    implementation("io.helidon.webserver:helidon-webserver")
    implementation("com.swirlds:swirlds-logging")
    runtimeOnly("com.hedera.pbj:pbj-grpc-helidon-config")
}

testModuleInfo {
    requires("org.junit.jupiter.params")
    requires("org.hiero.clpr.relay.core.test.fixtures")
    runtimeOnly("com.swirlds.config.impl")
}
