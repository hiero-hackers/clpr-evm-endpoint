// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "CLPR EVM Relay — JSON-RPC client for EVM nodes"

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind")
    api(project(":clpr-relay-core"))
    implementation("com.fasterxml.jackson.core:jackson-core")
}

testModuleInfo {
    requires("org.hiero.clpr.relay.evm.test.fixtures")
    requires("org.junit.jupiter.params")
    runtimeOnly("com.swirlds.config.impl")
}
