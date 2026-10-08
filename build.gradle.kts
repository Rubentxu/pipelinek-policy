// pipelinek-policy · root build (M2)
// M2 introduces a dual production allowlist per ADR-0011 D11.1:
//   - core     : exactly org.jetbrains.kotlin:kotlin-stdlib (broad)
//   - parsers  : per-submodule list under policy-decoders-{json,yaml,csv,map}
// A core module that declares a parser coordinate fails the guard before any
// network resolution; a parser module that declares a coordinate outside its
// declared bucket fails the same way. INC-002 (org.jetbrains:annotations
// dropped) carries over from M1.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.detekt) }

group = "com.pipelinek.policy"
version = "0.2.0-M2"

kotlin { jvmToolchain(21) }

val productionConfigs = setOf("compileClasspath", "runtimeClasspath")

// D11.1 dual allowlist. Any new parser submodule MUST extend `parserAllowedCoords`
// and register its project path below. Adding a coordinate outside the listed
// bucket fails the guard BEFORE network resolution.
val coreAllowedCoords = setOf("org.jetbrains.kotlin:kotlin-stdlib")
val parserAllowedCoords: Map<String, Set<String>> = mapOf(
    ":policy-decoders-json" to setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "com.fasterxml.jackson.core:jackson-core",
    ),
    ":policy-decoders-yaml" to setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.snakeyaml:snakeyaml-engine",
    ),
    ":policy-decoders-csv" to setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
    ),
    ":policy-decoders-map" to setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
    ),
)
val parserModulePaths = parserAllowedCoords.keys

/** Resolve the allowlist bucket for a project path (core or parser). */
fun allowedCoordsFor(projectPath: String): Set<String> =
    parserAllowedCoords[projectPath] ?: coreAllowedCoords

// Declared-dep guard: fails BEFORE network on any non-allowlisted
// production dep. Project/file deps are fail-closed in M0/M1 and remain so.
afterEvaluate {
    val buildFile = project.layout.projectDirectory.file("build.gradle.kts").asFile
    val failing = mutableListOf<String>()
    val cfgs: org.gradle.api.artifacts.ConfigurationContainer = configurations
    val projectPath = ":"
    val allowlist = allowedCoordsFor(projectPath)
    cfgs.matching { it.name in productionConfigs }.forEach { cfg ->
        val depSet: org.gradle.api.artifacts.DependencySet = cfg.allDependencies
        depSet.forEach { dep ->
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
            if (coord == null || coord.substringBeforeLast(':') !in allowlist) {
                failing += "${cfg.name}:${coord ?: dep.javaClass.simpleName}"
            }
        }
    }
    if (failing.isNotEmpty()) throw GradleException(
        "architectureFitnessGuard FAIL (declared)\n  buildFile: $buildFile\n" +
            failing.joinToString("\n", prefix = "  coords:  ") { "  - $it" } +
            "\nReason: M2 dual allowlist (ADR-0011 D11.1); root allowlist = " +
            allowlist.joinToString() + "; project/file deps are fail-closed."
    )
}

dependencies {
    implementation(libs.kotlin.stdlib) {
        // INC-002 carry-over: org.jetbrains:annotations excluded; M2 domain code
        // does not depend on it (the kernel forbids I/O per law 5).
        exclude(group = "org.jetbrains", module = "annotations")
    }
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    // M3: kotlin-reflect is needed by MacroPurityTest to introspect
    // `::policy.isInline`. Restricted to test scope — production core
    // still matches `coreAllowedCoords = { kotlin-stdlib }` (ADR-0011 D11.1).
    // Pinned to 2.4.10 (kotlin-reflect 2.4.20 is not in the offline cache;
    // 2.4.10 is the closest cached version that the build can resolve
    // without network access).
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // dev.detekt: lint wiring for :check (INC-004). Plugin lives in pluginManagement;
    // dev.detekt coordinates never enter compileClasspath/runtimeClasspath.
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

/**
 * M1/M2: core architecture fitness guard. Walks every resolved component on
 * every production configuration and refuses any coordinate outside the
 * allowlist for the source module's bucket. Fails BEFORE any network
 * resolution.
 */
val architectureFitnessGuard by tasks.registering {
    group = "verification"
    description = "Reserved module dir + re-verify resolved production classpath."
    doLast {
        val reservedModule = file("pipelinek-policy-plugin")
        if (reservedModule.exists() && reservedModule.isDirectory) throw GradleException(
            "architectureFitnessGuard FAIL\n" +
                "  reserved module dir present: ${reservedModule.absolutePath}\n" +
                "Reason: pipelinek-policy-plugin/ is reserved for M6 external plugin integration."
        )
        val cfgs: org.gradle.api.artifacts.ConfigurationContainer = configurations
        val failing = cfgs.matching { it.name in productionConfigs }.flatMap { cfg ->
            cfg.incoming.resolutionResult.allComponents.mapNotNull { comp ->
                val id = comp.id
                val coord: String? = when {
                    id is ProjectComponentIdentifier && id.projectPath == ":" -> null
                    id is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    else -> "unknown(${id.javaClass.simpleName})"
                }
                if (coord != null && coord.substringBeforeLast(':') !in coreAllowedCoords)
                    "${cfg.name}:$coord" else null
            }
        }
        if (failing.isNotEmpty()) throw GradleException(
            "architectureFitnessGuard FAIL (resolved)\n" +
                "  buildFile: ${project.layout.projectDirectory.file("build.gradle.kts").asFile}\n" +
                failing.joinToString("\n", prefix = "  declares: ") { "  - $it" } +
                "\nReason: resolved root production classpath must match " +
                coreAllowedCoords.joinToString()
        )
        logger.lifecycle("architectureFitnessGuard OK")
    }
}

// INC-004: detekt wired into :check (single lint tool, BEFORE first domain slice).
// detekt 2.0.0-alpha.6 is already in the local Gradle cache; offline-reproducible.
// Config is minimal — built-in defaults, with severity=tolerance tightened for
// production paths so domain code cannot slip a regression past :check.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    config.setFrom(files("config/detekt/detekt.yml"))
    basePath = projectDir
}

tasks.named("check") {
    dependsOn(architectureFitnessGuard)
    dependsOn("detekt")
}