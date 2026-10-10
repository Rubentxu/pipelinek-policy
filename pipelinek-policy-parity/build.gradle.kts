// pipelinek-policy-parity — H1.2 adapter parity certification.
//
// WHY THIS MODULE EXISTS
//
// `pipelinek-policy-cli` and `pipelinek-policy-plugin` are siblings: neither
// depends on the other, and that is correct — they are two adapters over the
// same kernel and neither should know about the other. But it has a cost that
// H1.2 exposed: NO module could see both, so nothing could assert that the two
// adapters agree. The existing suites each tested their own adapter, which
// proves each works and proves nothing about whether they decide the same thing.
//
// A parity claim is therefore not merely a good idea, it is untestable in the
// current layout. This module exists to make it testable.
//
// Both edges are `testImplementation` ON PURPOSE. If either were `implementation`,
// one adapter would be on the other's runtime classpath and the hexagonal
// boundary (law 11: the public external-plugin seam, no adapter-specific
// coupling) would be broken by the test suite that is supposed to defend it.
//
// It ships no production code. It is not part of any distribution artifact, and
// `:check` covers it like any other module so the parity tests cannot rot
// unnoticed.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pinned to the SAME version `pipelinek-policy-plugin` compiles against, so the
// parity test never asserts against a different SDK than the adapter it drives.
val sdkVersion = "0.47.0"

dependencies {
    // The shared kernel under test. Both adapters are pulled in for TESTS only.
    implementation(project(":"))
    testImplementation(project(":pipelinek-policy-cli"))
    testImplementation(project(":pipelinek-policy-plugin"))
    testImplementation(project(":policy-decoders-json"))

    // The plugin's public types (`PolicyCheckOutput`, `PolicyCheckStepDefinition`)
    // implement SDK supertypes (`TypedStepOutput`, `StepDefinition`). Gradle does
    // not expose those through a consumer's `implementation` edge, so reading
    // `output.verdict` from here fails to compile without them declared.
    //
    // TEST-ONLY on purpose, and mirroring what `pipelinek-policy-plugin` itself
    // declares. This is not the policy module depending on PipelineK (law 6
    // forbids that in the core); it is an adapter's test surface making the same
    // dependency visible so the parity assertion can run.
    testImplementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
    testImplementation("dev.rubentxu.pipeline.v2:pipeline-domain")

    implementation(libs.kotlin.stdlib) {
        exclude(group = "org.jetbrains", module = "annotations")
    }

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(providers.gradleProperty("testJvm").getOrElse("21").toInt()) }

tasks.test {
    useJUnitPlatform()
}
