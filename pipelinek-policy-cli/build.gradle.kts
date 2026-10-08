// pipelinek-policy-cli · M9 CLI y Agent DX
// The only module with filesystem/argv surface. Dispatch + emitters live
// here; the policy core stays pure (law 5). Zero new external coordinates:
// the bucket is kotlin-stdlib plus the decoder submodules' format libs.

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    // Pure policy core + format decoders (SPI).
    implementation(project(":"))
    implementation(project(":policy-decoders-json"))
    implementation(project(":policy-decoders-yaml"))
    implementation(project(":policy-decoders-csv"))
    implementation(project(":policy-decoders-map"))
    implementation(libs.kotlin.stdlib) {
        exclude(group = "org.jetbrains", module = "annotations")
    }

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.pipelinek.policy.cli.PolicyCliKt")
}

// M10 (REQ 02): parameterizable test JVM for the compiler matrix.
kotlin { jvmToolchain(providers.gradleProperty("testJvm").getOrElse("21").toInt()) }

tasks.test {
    useJUnitPlatform()
    // M10 characterization: 64 MiB CSV materializes ~1M rows (≈6M ValueNode
    // cells + per-cell source anchors) — needs headroom. OBSERVED OOM at default.
    maxHeapSize = "4g"
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

tasks.named("check") {
    dependsOn("test")
}

/**
 * M10 REQ 05c · On-demand 1 GiB CSV characterization entry point (NOT CI).
 * Usage: ./gradle-jdk21.sh :pipelinek-policy-cli:certCsv1gib -PcertCsvBytes=1073741824
 */
val certCsv1gib by tasks.registering(JavaExec::class) {
    group = "m10"
    description = "Run the on-demand CSV characterization harness (default 1 GiB)."
    mainClass.set("com.pipelinek.policy.cli.cert.CertCsvMain")
    classpath = sourceSets["test"].runtimeClasspath
    if (project.hasProperty("certCsvBytes")) {
        args(project.property("certCsvBytes").toString())
    }
    jvmArgs("-Xmx24g")
}
