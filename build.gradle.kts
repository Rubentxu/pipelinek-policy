// pipelinek-policy · root build (M0 partial bootstrap)
// M0 production allowlist (EXACT coords only): org.jetbrains.kotlin:kotlin-stdlib,
// org.jetbrains:annotations. Pinning lives in gradle/libs.versions.toml.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { alias(libs.plugins.kotlin.jvm) }

group = "com.pipelinek.policy"
version = "0.1.0-M0"

kotlin { jvmToolchain(21) }

val productionConfigs = setOf("compileClasspath", "runtimeClasspath")
val allowedCoords = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")

// Declared-dep guard: fails BEFORE network on any non-allowlisted
// production dep. Project/file deps are fail-closed in M0.
afterEvaluate {
    val buildFile = project.layout.projectDirectory.file("build.gradle.kts").asFile
    val failing = mutableListOf<String>()
    val cfgs: org.gradle.api.artifacts.ConfigurationContainer = configurations
    cfgs.matching { it.name in productionConfigs }.forEach { cfg ->
        val depSet: org.gradle.api.artifacts.DependencySet = cfg.allDependencies
        depSet.forEach { dep ->
            val coord: String? = when (dep) {
                is org.gradle.api.artifacts.ExternalModuleDependency ->
                    "${dep.group}:${dep.name}:${dep.version}"
                is org.gradle.api.artifacts.ProjectDependency ->
                    "project(${dep.group ?: dep.path})"
                is org.gradle.api.artifacts.FileCollectionDependency -> {
                    val names = dep.files.files.map { f -> f.name }.joinToString()
                    "file($names)"
                }
                else -> null
            }
            if (coord == null || coord.substringBeforeLast(':') !in allowedCoords) {
                failing += "${cfg.name}:${coord ?: dep.javaClass.simpleName}"
            }
        }
    }
    if (failing.isNotEmpty()) throw GradleException(
        "architectureFitnessGuard FAIL (declared)\n  buildFile: $buildFile\n" +
            failing.joinToString("\n", prefix = "  coords:  ") { "  - $it" } +
            "\nReason: M0 production allowlist is exactly " + allowedCoords.joinToString() +
            "; project/file deps are fail-closed."
    )
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.jetbrains.annotations)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

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
                if (coord != null && coord.substringBeforeLast(':') !in allowedCoords)
                    "${cfg.name}:$coord" else null
            }
        }
        if (failing.isNotEmpty()) throw GradleException(
            "architectureFitnessGuard FAIL (resolved)\n" +
                "  buildFile: ${project.layout.projectDirectory.file("build.gradle.kts").asFile}\n" +
                failing.joinToString("\n", prefix = "  declares: ") { "  - $it" } +
                "\nReason: resolved production classpath must match " + allowedCoords.joinToString()
        )
        logger.lifecycle("architectureFitnessGuard OK")
    }
}

tasks.named("check") { dependsOn(architectureFitnessGuard) }