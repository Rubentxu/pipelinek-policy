// pipelinek-policy-plugin · M6 external plugin
// The ONLY module that faces the PipelineK public plugin seam. It resolves the
// published SDK contracts (0.47.0 BOM from mavenLocal) plus this repo's policy
// core and decoder subprojects, and is discovered by the runtime exclusively
// through ServiceLoader contributors. Zero pipeline-kotlin core edits.

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    // M6: @Serializable input/output, same shape as the SDK example plugins.
    // The runtime (pipelinek distribution) provides kotlinx-serialization at
    // runtime; compileOnly mirrors example-block-plugin's wiring.
    kotlin("plugin.serialization") version "2.4.10"
}

val sdkVersion = "0.47.0"

dependencies {
    // Published PipelineK SDK contracts, pinned by the BOM (resolution only).
    implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
    implementation("dev.rubentxu.pipeline.v2:pipeline-domain")
    implementation("dev.rubentxu.pipeline.v2:pipeline-events")
    implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api")

    // This repo: pure policy core + format decoders (the handler stays pure).
    implementation(project(":"))
    implementation(project(":policy-decoders-json"))
    implementation(project(":policy-decoders-yaml"))
    implementation(project(":policy-decoders-csv"))
    implementation(project(":policy-decoders-map"))

    implementation(libs.kotlin.stdlib) {
        exclude(group = "org.jetbrains", module = "annotations")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

/**
 * M6 WU-7 · the plugin JAR consumed by an installed distribution via `--plugin-jar`.
 *
 * FAT jar on purpose: the distribution provides the SDK contracts
 * (pipeline-domain/events/scripting-api) and kotlin-stdlib, but NOT this
 * repo's policy core, decoders, or their format libraries — those ship inside
 * the plugin jar. SDK/stdlib classes are EXCLUDED so the runtime's own copies
 * win (no duplicate classes on the app classpath).
 */
val pluginFatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("pipelinek-policy-plugin")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Implementation-Title" to "pipelinek-policy-plugin", "Implementation-Version" to "0.1.0")
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().files.filterNot { f ->
            // Provided by the distribution at runtime.
            f.name.startsWith("pipeline-domain") ||
                f.name.startsWith("pipeline-events") ||
                f.name.startsWith("pipeline-output") ||
                f.name.startsWith("pipeline-scripting-api") ||
                f.name.startsWith("kotlin-stdlib") ||
                f.name.startsWith("annotations-")
        }.map { f -> zipTree(f) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
}

tasks.named("check") { dependsOn(pluginFatJar) }

// ----------------------------------------------------------------------------
// M6 REQ-06 · provenance + manifest document, both derived, neither typed by hand.
//
// Pattern: examples/example-block-plugin (S6/I). The ServiceLoader descriptor
// claims the artifact CONTRIBUTES; META-INF/pipelinek/plugin-manifest.json is
// the claim about WHAT. Without it, PreLoadPluginAdmission refuses the artifact
// ("no manifest at ...") and every run carrying this jar exits 2.
//
// The digest is measured over the artifact's own content EXCLUDING the two
// documents that carry it (release properties + manifest), so the value has a
// fixed point.
// ----------------------------------------------------------------------------

val policyReleaseProps =
    layout.buildDirectory.file("resources/main/META-INF/pipelinek-policy-release.properties")

val computePolicyRelease = tasks.register("computePolicyRelease") {
    group = "policy-plugin"
    description = "Measure the plugin artifact's SHA-256 and write its release provenance."

    val propsFile = policyReleaseProps
    val releaseVersion = "0.1.0"

    dependsOn("compileKotlin", "processResources")
    outputs.file(propsFile)

    doLast {
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        val out = propsFile.get().asFile
        val excluded = setOf(
            out.absolutePath,
            out.parentFile.resolve("pipelinek/plugin-manifest.json").absolutePath,
        )

        val files = listOf(classesDir, resourcesDir)
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.absolutePath !in excluded }
                    .map { it.absolutePath }
                    .toList()
            }
            .sorted()

        require(files.isNotEmpty()) {
            "no class or resource files to hash; refusing to emit provenance for an empty artifact"
        }

        // Hash each file's RELATIVE name with its bytes, in sorted order. The name matters as
        // much as the content: two artifacts that swap a class for a resource of the same
        // length would otherwise hash identically.
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in files) {
            val file = file(path)
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(file.readBytes())
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }

        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("pipelinek.policy.release.version=$releaseVersion")
                appendLine("pipelinek.policy.release.digest=sha256:$hex")
            },
        )
        println("policy-plugin: provenance written to $out (digest=sha256:${hex.take(16)}...)")
    }
}

val policyManifest =
    layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

/**
 * The SDK is on the runtime classpath of the BUILD-TIME emitter only.
 *
 * Every production dependency is `compileOnly`-style from the host's
 * perspective, so the emitter needs an explicit classpath carrying the SDK —
 * which does not leak into the JAR.
 */
val sdkForManifestEmission by configurations.creating {
    extendsFrom(configurations.compileOnly.get())
}

val emitPolicyManifest = tasks.register<JavaExec>("emitPolicyManifest") {
    group = "policy-plugin"
    description = "M6 REQ-06: emit the machine-readable plugin manifest into the artifact."

    dependsOn(computePolicyRelease)
    mainClass.set("com.pipelinek.policy.plugin.PolicyCheckContributorKt")

    classpath = files(
        sourceSets["main"].runtimeClasspath,
        layout.buildDirectory.dir("resources/main"),
        configurations.named("sdkForManifestEmission"),
    )

    inputs.files(policyReleaseProps)
    outputs.file(policyManifest)

    val out = policyManifest
    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doFirst {
        out.get().asFile.parentFile.mkdirs()
        // The buffer lives for the whole configuration, so a second execution in the same
        // build would APPEND to the first document and emit concatenated JSON.
        captured.reset()
    }

    doLast {
        val text = captured.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            throw GradleException(
                "M6 REQ-06: manifest emission produced no output. The plugin would ship " +
                    "without META-INF/pipelinek/plugin-manifest.json and admission would " +
                    "refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("policy-plugin: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") { finalizedBy(emitPolicyManifest) }
tasks.named("jar") { dependsOn(emitPolicyManifest) }
pluginFatJar { dependsOn(emitPolicyManifest) }

// Submodule-level guard mirroring the root build's plugin bucket
// (pluginModuleAllowedCoords). Fails at the declared-dep stage.
val productionConfigs = setOf("compileClasspath", "runtimeClasspath")
val allowedCoords = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "dev.rubentxu.pipeline.v2:pipeline-domain",
    "dev.rubentxu.pipeline.v2:pipeline-events",
    "dev.rubentxu.pipeline.v2:pipeline-output",
    "dev.rubentxu.pipeline.v2:pipeline-scripting-api",
    "dev.rubentxu.pipeline.v2:pipeline-sdk-bom",
)
val allowedProjectPaths = setOf(":", ":policy-decoders-json", ":policy-decoders-yaml", ":policy-decoders-csv", ":policy-decoders-map")

afterEvaluate {
    val failing = mutableListOf<String>()
    val cfgs: org.gradle.api.artifacts.ConfigurationContainer = configurations
    cfgs.matching { it.name in productionConfigs }.forEach { cfg ->
        cfg.allDependencies.forEach { dep ->
            val coord: String? = when (dep) {
                is org.gradle.api.artifacts.ExternalModuleDependency ->
                    "${dep.group}:${dep.name}:${dep.version}"
                is org.gradle.api.artifacts.ProjectDependency -> "project(${dep.path})"
                else -> null
            }
            val ok = when (dep) {
                is org.gradle.api.artifacts.ProjectDependency -> dep.path in allowedProjectPaths
                else -> coord?.substringBeforeLast(':') in allowedCoords
            }
            if (!ok) failing += "${cfg.name}:${coord ?: dep.javaClass.simpleName}"
        }
    }
    if (failing.isNotEmpty()) throw GradleException(
        "plugin module allowlist FAIL\n" + failing.joinToString("\n  - ", prefix = "  - "),
    )
}
