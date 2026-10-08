// policy-decoders-yaml · per ADR-0011 D11.2
// Pure parser submodule: depends only on the core project and on
// org.snakeyaml:snakeyaml-engine (the maintained fork that exposes the
// event API needed for alias caps and source-span tracking).

plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":"))
    implementation(libs.snakeyaml.engine)
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
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

val productionConfigs = setOf("compileClasspath", "runtimeClasspath")
val allowedCoords = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.snakeyaml:snakeyaml-engine",
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
        "architectureFitnessGuard FAIL (declared) in policy-decoders-yaml\n" +
            "  buildFile: $buildFile\n" +
            failing.joinToString("\n", prefix = "  coords:  ") { "  - $it" } +
            "\nReason: parsers bucket allowlist is exactly " + allowedCoords.joinToString()
    )
}

tasks.named("check") {
    dependsOn("test")
}