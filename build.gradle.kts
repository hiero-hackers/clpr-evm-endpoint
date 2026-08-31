// SPDX-License-Identifier: Apache-2.0
// Root build file — keep lean; per-module config lives in each subproject.
import org.gradlex.javamodule.dependencies.dsl.AllDirectives

// The hiero javadoc convention adds '-Xwerror'; override across all subprojects so that
// spurious classfile warnings (e.g. from transitive annotation jars) do not fail the build.
subprojects {
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    // Point swirlds-logging (com.swirlds.logging) at its config file. Its
    // DefaultLoggingSystem reads the LOG_CONFIG_PATH *environment variable* (no
    // system-property fallback), else falls back to `log.properties` relative to
    // the JVM's CWD — which under Gradle is this subproject dir, where no such
    // file exists. Without this it logs a per-cycle "Unable to load logging
    // configuration from path" warning. A classpath resource is never consulted,
    // so the file must be referenced by absolute filesystem path. Applies to every
    // JVM-forking task (Test, JavaExec) across all subprojects.
    tasks.withType<Test>().configureEach {
        environment("LOG_CONFIG_PATH", "${rootProject.projectDir}/log.test.properties")
    }
    tasks.withType<JavaExec>().configureEach {
        environment("LOG_CONFIG_PATH", "${rootProject.projectDir}/log.properties")
    }

    // JSpecify nullability annotations are used project-wide; expose them as compileOnly
    // to any module applying a hiero library/application convention.
    listOf("org.hiero.gradle.module.library", "org.hiero.gradle.module.application").forEach {
        pluginId ->
        plugins.withId(pluginId) {
            dependencies { "compileOnly"("org.jspecify:jspecify") }
            // Every module with tests uses JUnit Jupiter + AssertJ. Skip modules
            // without a src/test/java directory — hiero's checkModuleDirectivesScope
            // rejects unused test requires.
            if (file("src/test/java").exists()) {
                extensions.configure<AllDirectives>("testModuleInfo") {
                    requires("org.junit.jupiter.api")
                    requires("org.assertj.core")
                }
            }
        }
    }
}
