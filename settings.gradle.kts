// SPDX-License-Identifier: Apache-2.0
import org.gradlex.javamodule.moduleinfo.ExtraJavaModuleInfoPluginExtension

plugins {
    id("org.hiero.gradle.build") version "0.7.6"
    id("com.hedera.pbj.pbj-compiler") version "0.15.6" apply false
}

rootProject.name = "clpr-relay"

javaModules {
    directory(".") {
        group = "org.hiero.clpr"
        // hiero-dependency-versions is auto-registered by the hiero build plugin
        exclusions.add("hiero-dependency-versions")
        exclusions.add("docs")
        exclusions.add("gradle")
    }
}

// JPMS patches for transitive deps that need explicit module-info declarations.
// Originally this list was just the annotation-only jars pulled in by pbj-runtime as
// compile-only transitives, but it has since grown to cover other transitives (swirlds-logging's
// auto-service-annotations, the testcontainers + docker-java stack, etc.). The common thread is
// that these jars are NOT on mainRuntimeClasspath so the ExtraJavaModuleInfoTransform's default
// 'requireAllDefinedDependencies()' behavior fails to look up versions. We patch them with
// explicit module entries that export all packages without metadata lookup.
@Suppress("UnstableApiUsage")
gradle.lifecycle.beforeProject {
    fun configureModules(pluginId: String) =
        plugins.withId(pluginId) {
            the<ExtraJavaModuleInfoPluginExtension>().apply {
                // Transitive-only: spotbugs-annotations and jsr305 still arrive via other deps
                // (pbj-runtime, swirlds-logging). We no longer import from them — see JSpecify —
                // but they must still be mapped to JPMS modules so the ExtraJavaModuleInfoTransform
                // can patch their JARs.
                module(
                    "com.github.spotbugs:spotbugs-annotations",
                    "com.github.spotbugs.annotations",
                ) {
                    exportAllPackages()
                    requires("java.annotation")
                }
                module("com.google.code.findbugs:jsr305", "java.annotation") { exportAllPackages() }
                module(
                    "com.google.auto.service:auto-service-annotations",
                    "com.google.auto.service",
                ) {
                    exportAllPackages()
                }
                module("org.antlr:antlr4-runtime", "org.antlr.antlr4.runtime") {
                    exportAllPackages()
                }
                module("com.google.protobuf:protobuf-java", "com.google.protobuf") {
                    exportAllPackages()
                }
                module("com.google.protobuf:protobuf-java-util", "com.google.protobuf.util") {
                    exportAllPackages()
                }
                module("org.mockito:mockito-core", "org.mockito") {
                    exportAllPackages()
                    requires("java.instrument")
                    requires("jdk.unsupported")
                    requires("net.bytebuddy")
                    requires("net.bytebuddy.agent")
                    requires("org.objenesis")
                }
                module("org.mockito:mockito-junit-jupiter", "org.mockito.junit.jupiter") {
                    exportAllPackages()
                    requires("org.mockito")
                    requires("org.junit.jupiter.api")
                }
                // Mockito transitive test-only deps — override hiero default
                // (requireAllDefinedDependencies)
                // because these are NOT on mainRuntimeClasspath and the version lookup would fail.
                module("org.objenesis:objenesis", "org.objenesis") { exportAllPackages() }
                // byte-buddy jars already contain a real module-info; preserve it instead of
                // patching.
                module("net.bytebuddy:byte-buddy", "net.bytebuddy") { preserveExisting() }
                module("net.bytebuddy:byte-buddy-agent", "net.bytebuddy.agent") {
                    preserveExisting()
                }

                // Testcontainers and its transitive dependencies (test-only).
                module("org.testcontainers:testcontainers", "org.testcontainers") {
                    exportAllPackages()
                    requiresTransitive("junit")
                    requires("com.github.dockerjava.api")
                    requires("com.github.dockerjava.transport")
                    requires("com.github.dockerjava.transport.zerodep")
                    requires("com.fasterxml.jackson.annotation")
                    requires("org.apache.commons.compress")
                    requires("org.rnorth.ducttape")
                    requires("org.slf4j")
                    requires("java.sql")
                    requires("com.sun.jna")
                    uses("org.testcontainers.utility.ImageNameSubstitutor")
                    uses("org.testcontainers.core.CreateContainerCmdModifier")
                    uses("org.testcontainers.dockerclient.DockerClientProviderStrategy")
                }
                module("org.testcontainers:junit-jupiter", "org.testcontainers.junit.jupiter") {
                    exportAllPackages()
                    requires("org.testcontainers")
                    requires("org.junit.jupiter.api")
                }
                module("com.github.docker-java:docker-java-api", "com.github.dockerjava.api") {
                    exportAllPackages()
                    requires("org.slf4j")
                }
                module(
                    "com.github.docker-java:docker-java-transport",
                    "com.github.dockerjava.transport",
                ) {
                    exportAllPackages()
                    requires("org.slf4j")
                }
                module(
                    "com.github.docker-java:docker-java-transport-zerodep",
                    "com.github.dockerjava.transport.zerodep",
                ) {
                    exportAllPackages()
                    requires("com.github.dockerjava.transport")
                    requires("org.slf4j")
                }
                module("org.apache.commons:commons-compress", "org.apache.commons.compress") {
                    patchRealModule()
                    exportAllPackages()
                }
                module("org.rnorth.duct-tape:duct-tape", "org.rnorth.ducttape") {
                    exportAllPackages()
                    requires("org.slf4j")
                }
                // slf4j-api ships with a real module-info; keep it.
                module("org.slf4j:slf4j-api", "org.slf4j") { preserveExisting() }
                module("org.slf4j:jul-to-slf4j", "org.slf4j.jul") { exportAllPackages() }
                // JUnit 4 is a transitive of testcontainers (testcontainers extends
                // org.junit.rules.TestRule for legacy reasons).
                module("junit:junit", "junit") { exportAllPackages() }
                // JetBrains annotations jar is an automatic module — patch it to a real module.
                module("org.jetbrains:annotations", "org.jetbrains.annotations") {
                    exportAllPackages()
                }
                // BouncyCastle jdk18on jars are multi-release JARs shipping a real module-info;
                // keep it.
                module("org.bouncycastle:bcprov-jdk18on", "org.bouncycastle.provider") {
                    preserveExisting()
                }
                module("org.bouncycastle:bcutil-jdk18on", "org.bouncycastle.util") {
                    preserveExisting()
                }
                module("org.bouncycastle:bcpkix-jdk18on", "org.bouncycastle.pkix") {
                    preserveExisting()
                }
            }
        }
    configureModules("org.hiero.gradle.module.library")
    configureModules("org.hiero.gradle.module.application")
}
