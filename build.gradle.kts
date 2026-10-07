import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.pipelinek.policy"
version = "0.1.0-M0"

kotlin {
    jvmToolchain(21)
}

// =====================================================================
// Architecture Fitness Guard (REQ-Architecture-Fitness-Guard)
// =====================================================================
// Two failure surfaces, no helpers, no loops duplicated:
//   * declared: every production classpath dependency must match the
//     EXACT allowlist `org.jetbrains.kotlin:kotlin-stdlib` or
//     `org.jetbrains:annotations`. Project / file dependencies are
//     fail-closed at configuration time so typos and reserved groups
//     surface before any network resolution is attempted.
//   * resolved: every ModuleComponentIdentifier on the resolved
//     production classpath must match the same EXACT allowlist. The
//     root project itself (`:`) is skipped.

val ROOT_PROJECT_PATH = ":"

val allowedCoords = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations"
)

fun failGuard(msg: String): Nothing = throw GradleException(msg)

afterEvaluate(closureOf<org.gradle.api.Project> {
    val p = this
    val declaredFailing = mutableListOf<String>()
    p.configurations
        .filter { it.name in setOf("compileClasspath", "runtimeClasspath") }
        .forEach { cfg ->
            cfg.allDependencies.forEach { dep ->
                val coord = when (dep) {
                    is org.gradle.api.artifacts.ExternalModuleDependency ->
                        "${dep.group}:${dep.name}"
                    is org.gradle.api.artifacts.ProjectDependency ->
                        "project(${dep.group})"
                    is org.gradle.api.artifacts.FileCollectionDependency ->
                        "file(${dep.files.files.joinToString { it.name }})"
                    else -> "unknown(${dep.javaClass.simpleName})"
                }
                if (coord !in allowedCoords) declaredFailing += "${cfg.name}:$coord"
            }
        }
    if (declaredFailing.isNotEmpty()) {
        failGuard(
            "architectureFitnessGuard FAIL (declared)\n" +
                "  buildFile: ${p.buildFile}\n" +
                declaredFailing.joinToString("\n", prefix = "  coords: ") { "  $it" } +
                "\nReason: production allowlist is exactly " + allowedCoords.joinToString() +
                "; non-external deps are fail-closed."
        )
    }
})

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.jetbrains.annotations)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

val architectureFitnessGuard by tasks.registering {
    group = "verification"
    description = "Refuses reserved module dir + re-checks resolved production classpath."

    doLast {
        // 1. Reserved module directory
        val reservedModule = file("pipelinek-policy-plugin")
        if (reservedModule.exists() && reservedModule.isDirectory) {
            failGuard(
                "architectureFitnessGuard FAIL\n" +
                        "  reserved module dir present: ${reservedModule.absolutePath}\n" +
                        "Reason: pipelinek-policy-plugin/ is reserved for M6 external " +
                        "plugin integration."
            )
        }
        // 2. Resolved production classpath
        val components = configurations
            .filter { it.name in setOf("compileClasspath", "runtimeClasspath") }
            .flatMap { cfg ->
                try {
                    cfg.incoming.resolutionResult.allComponents.toList()
                } catch (e: Throwable) {
                    failGuard(
                        "architectureFitnessGuard FAIL\n" +
                                "  scope: ${cfg.name} (resolved)\n" +
                                "  Reason: could not inspect resolved components: ${e.message}"
                    )
            } }
        val offending = components.mapNotNull { comp ->
            val id = comp.id
            when {
                id is ProjectComponentIdentifier && id.projectPath == ROOT_PROJECT_PATH -> null
                id is ModuleComponentIdentifier ->
                    if ("${id.group}:${id.module}" !in allowedCoords)
                        "${id.group}:${id.module}:${id.version}" else null
                else -> "unknown(${id.javaClass.simpleName})"
            }
        }
        if (offending.isNotEmpty()) {
            failGuard(
                "architectureFitnessGuard FAIL (resolved)\n" +
                    "  buildFile: ${project.buildFile}\n" +
                    offending.joinToString("\n", prefix = "  declares: ") { "  $it" } +
                    "\nReason: resolved production classpath must match " +
                    allowedCoords.joinToString()
            )
        }
        logger.lifecycle("architectureFitnessGuard OK")
    }
}

tasks.named("check") { dependsOn(architectureFitnessGuard) }