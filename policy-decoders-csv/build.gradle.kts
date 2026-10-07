// policy-decoders-csv · per ADR-0011 D11.1
// Pure parser submodule: hand-rolled RFC 4180 streaming tokenizer on
// top of kotlin-stdlib only. No external parser libraries (law 6).

plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":"))
    implementation(libs.kotlin.stdlib) {
        exclude(group = "org.jetbrains", module = "annotations")
    }

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

val productionConfigs = setOf("compileClasspath", "runtimeClasspath")
val allowedCoords = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
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
                is org.gradle.api.artifacts.ProjectDependency ->
                    "project(${dep.path})"
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
        "architectureFitnessGuard FAIL (declared) in policy-decoders-csv\n" +
            "  buildFile: $buildFile\n" +
            failing.joinToString("\n", prefix = "  coords:  ") { "  - $it" } +
            "\nReason: parsers bucket allowlist is exactly " + allowedCoords.joinToString()
    )
}

tasks.named("check") {
    dependsOn("test")
}