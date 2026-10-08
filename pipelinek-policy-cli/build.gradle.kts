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

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

tasks.named("check") {
    dependsOn("test")
}
