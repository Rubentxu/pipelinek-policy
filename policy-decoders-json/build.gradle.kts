// policy-decoders-json · per ADR-0011 D11.2
// Pure parser submodule: depends only on the core project (which is itself
// pure-stdlib) and on jackson-core. The architectureFitnessGuard mirrors
// this allowlist in the root build and fails BEFORE network if a coordinate
// outside the declared bucket shows up here.

plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":"))
    implementation(libs.jackson.core)
    implementation(libs.kotlin.stdlib) {
        // INC-002 carry-over: no annotation processing, no stdlib transitive.
        exclude(group = "org.jetbrains", module = "annotations")
    }

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // UAT-001 cross-decoder parity harness lives in this submodule's tests
    // because JSON is the format the rest of the project normalises against;
    // the harness instantiates the YAML decoder on demand. The test source
    // set is unconstrained by the parsers bucket allowlist, so this project
    // dependency does NOT widen the production classpath.
    testImplementation(project(":policy-decoders-yaml"))
}

kotlin { jvmToolchain(providers.gradleProperty("testJvm").getOrElse("21").toInt()) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

// Submodule-level dual-allowlist guard (mirrored in the root build).
// The root `architectureFitnessGuard` checks the resolved classpath; this
// guard fails at the declared-dep stage so a future Jackson databind that
// sneaks in is caught at `:check` time WITHOUT touching the network.
val productionConfigs = setOf("compileClasspath", "runtimeClasspath")
val allowedCoords = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "com.fasterxml.jackson.core:jackson-core",
)
// Project deps (e.g. implementation(project(":"))) are part of the parsers
// bucket — they enter the production classpath transitively and must NOT
// pull parser coords from another bucket. Allowed project paths here are
// only the core `:app` project; cross-bucket project deps are fail-closed.
val allowedProjectPaths = setOf(":")

afterEvaluate {
    val buildFile = project.layout.projectDirectory.file("build.gradle.kts").asFile
    val failing = mutableListOf<String>()
    val cfgs: org.gradle.api.artifacts.ConfigurationContainer = configurations
    cfgs.matching { it.name in productionConfigs }.forEach { cfg ->
        cfg.allDependencies.forEach { dep ->
            val coord: String? = when (dep) {
                is org.gradle.api.artifacts.ExternalModuleDependency ->
                    "${dep.group}:${dep.name}:${dep.version}"
                is org.gradle.api.artifacts.ProjectDependency -> {
                    val path = dep.path
                    if (path in allowedProjectPaths) "project(${path})"
                    else "project(${path})"
                }
                is org.gradle.api.artifacts.FileCollectionDependency -> {
                    val names = dep.files.files.map { f -> f.name }.joinToString()
                    "file($names)"
                }
                else -> null
            }
            val allowed = coord != null && (
                (coord.startsWith("project(") && coord.removePrefix("project(").removeSuffix(")") in allowedProjectPaths) ||
                    (coord.startsWith("file(")) ||
                    (!coord.startsWith("project(") && !coord.startsWith("file(") && coord.substringBeforeLast(':') in allowedCoords)
            )
            if (!allowed) {
                failing += "${cfg.name}:${coord ?: dep.javaClass.simpleName}"
            }
        }
    }
    if (failing.isNotEmpty()) throw GradleException(
        "architectureFitnessGuard FAIL (declared) in policy-decoders-json\n" +
            "  buildFile: $buildFile\n" +
            failing.joinToString("\n", prefix = "  coords:  ") { "  - $it" } +
            "\nReason: parsers bucket allowlist is exactly " + allowedCoords.joinToString()
    )
}

tasks.named("check") {
    dependsOn("test")
}