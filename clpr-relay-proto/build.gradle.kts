// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("com.hedera.pbj.pbj-compiler")
}

description = "CLPR EVM Relay — Protobuf / PBJ generated types"

// We only need PBJ model classes; skip the PBJ-generated protobuf-compatibility tests because
// they require a protoc-compiled version of the same protos that we don't produce here.
pbj { generateTestClasses = false }

// Suppress spurious classfile warning from spotbugs-annotations referencing jsr305 internals,
// and other lint warnings on PBJ-generated code that we don't control.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-deprecation,-removal")
}

dependencies {
    api(platform(project(":hiero-dependency-versions")))
    api("com.hedera.pbj:pbj-runtime")
}
