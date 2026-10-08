// M4.A (spike) — `policy-fir-plugin` opt-in Gradle submodule.
//
// Boundary per design A1 + ADR-0011 D11.1 (third bucket). This submodule
// declares ONLY the `firPluginAllowedCoords` set as its production
// dependencies; any other coordinate would be rejected by the root
// `architectureFitnessGuard` subproject walk BEFORE network resolution.
//
// The submodule is `kotlin("jvm")` + `java-gradle-plugin`. `java-gradle-plugin`
// automatically generates the META-INF/services entry for
// `org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin` (we
// only have the auto-registration via SPI service loader + class subclass; the
// 2.4.x SPI does NOT require a `KotlinGradleSubplugin` annotation any more).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-gradle-plugin`
}

group = "com.pipelinek.policy"
version = "0.2.0-M4-fir-spike"

// Match the root toolchain so cross-module bytecode is interchangeable.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

dependencies {
    // `compileOnly` because the FIR plugin consumes the compiler embeddable
    // at runtime via the host project's classpath; it must NOT leak into the
    // production classpath of any non-FIR module.
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.scripting.compiler.embeddable)
    // The Gradle-side SPI (KotlinCompilerPluginSupportPlugin). compileOnly
    // because the host Gradle runtime injects it; we do not bundle it.
    compileOnly(libs.kotlin.gradle.plugin.api)

    // Test scope only — we mirror the root's testing capabilities.
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Test-side `implementation` so the gate tests can introspect the FIR
    // SPI surface (reflection probes) without leaking it into the runtime
    // production classpath.
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.scripting.compiler.embeddable)
}

// Wire detekt into the submodule so `gradle :check` walks both modules.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    config.setFrom(files("${rootDir}/config/detekt/detekt.yml"))
    basePath = projectDir
}

// Ensure the submodule is part of `:check` so the root-level guard fires for it too.
tasks.named("check") {
    dependsOn("detekt")
}