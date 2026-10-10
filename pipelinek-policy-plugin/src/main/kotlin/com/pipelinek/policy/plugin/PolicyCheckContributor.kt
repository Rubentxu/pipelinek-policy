package com.pipelinek.policy.plugin

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginEventContribution
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * M6 REQ-06 · the plugin's single declaration authority.
 *
 * The manifest is machine-readable (codec-encodable via [PluginManifestCodec]),
 * declares the API range this plugin was built against, and its contributions
 * match EXACTLY what the contributors register — the same invariant the core
 * admission gate cross-checks (declared steps/events vs registered contracts).
 *
 * Provenance is fail-closed (SDK pattern, see example-block-plugin): the
 * version and digest are read from [RELEASE_PROPERTIES_RESOURCE], written at
 * BUILD time by a Gradle task that MEASURES the artifact. A hand-typed digest
 * is worse than none, because admission would read it as a measured identity.
 */
object PolicyPluginDeclaration {

    /** Where the build writes the measured release provenance. */
    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/pipelinek-policy-release.properties"

    /** Same range the shipped 0.47.x SDK plugins declare. */
    val API_RANGE: PipelineKApiRange = PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))

    val PLUGIN_REF = ResourceRefs.plugin("pipelinek-policy", "policy")

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) { "SemVer must have 3 numeric components (got '$raw')" }
        return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    /**
     * Fail-closed provenance: never accept a hand-typed digest.
     *
     * Resolution order: system property override, then the release-properties
     * document inside the artifact, then REFUSE. Refusing here (instead of
     * defaulting) is what keeps "declared identity" honest.
     */
    fun release(
        classLoader: ClassLoader = PolicyPluginDeclaration::class.java.classLoader,
    ): PluginReleaseRef {
        val props = releaseProperties(classLoader)
        val versionRaw = System.getProperty("pipelinek.policy.release.version")
            ?: props["pipelinek.policy.release.version"]
            ?: error(
                "Missing version provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE " +
                    "in the JAR). The plugin refuses to declare itself without it.",
            )
        val digestRaw = System.getProperty("pipelinek.policy.release.digest")
            ?: props["pipelinek.policy.release.digest"]
            ?: error(
                "Missing digest provenance (no system property and no $RELEASE_PROPERTIES_RESOURCE).",
            )
        return PluginReleaseRef(
            plugin = PLUGIN_REF,
            version = parseSemVer(versionRaw),
            digest = Digest(digestRaw),
        )
    }

    fun providerMetadata(
        classLoader: ClassLoader = PolicyPluginDeclaration::class.java.classLoader,
    ) = StepProviderMetadataFactory.create(
        release = release(classLoader),
    )

    /**
     * One Step, no directives, one event.
     *
     * The declared capability set is the contract's own `requiredCapabilities`
     * rather than a transcription of it: the admission cross-check compares the
     * manifest against the contract exactly, so a divergence here would refuse
     * this plugin for a difference that cannot exist.
     */
    fun manifest(
        classLoader: ClassLoader = PolicyPluginDeclaration::class.java.classLoader,
    ): PluginManifest {
        val provider = providerMetadata(classLoader)
        return PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = provider.plugin,
            release = provider.release,
            apiRange = API_RANGE,
            publisher = provider.publisher,
            families = provider.families,
            delivery = provider.delivery,
            trust = provider.trust,
            contributions = PluginContributions(
                steps = listOf(
                    PluginStepContribution(
                        PolicyCheckStepDefinition.KEY,
                        PolicyCheckStepDefinition.contract.requiredCapabilities,
                    ),
                ),
                directives = emptyList(),
                events = listOf(
                    PluginEventContribution(PolicyCheckStepDefinition.REPORTED_EVENT_KIND),
                ),
                capabilities = emptySet(),
            ),
        )
    }

    /**
     * Read the plugin's own `release.properties` off the classpath.
     *
     * Deliberately NOT the ingress resource budget. That budget exists to
     * bound untrusted INPUT; this is the plugin's own packaged metadata, so
     * 64 MiB of headroom would be a number that reads as protection while
     * protecting nothing. The cap here is a sanity bound on a file that
     * should be a few hundred bytes — small enough that a corrupt or
     * substituted jar cannot demand heap it has no business holding, and
     * stated here rather than imported so nobody mistakes it for the
     * ingress budget it is not.
     */
    private const val RELEASE_PROPERTIES_MAX_BYTES = 64 * 1024

    internal fun releaseProperties(classLoader: ClassLoader): Map<String, String> {
        val resource = classLoader.getResource(RELEASE_PROPERTIES_RESOURCE) ?: return emptyMap()
        // Refuse rather than truncate. A properties file cut in half would
        // parse into a plausible-looking map with missing keys, which is
        // worse than saying nothing: the caller cannot tell a truncated
        // release description from a complete one.
        val text = resource.openStream().use { stream ->
            val bytes = stream.readNBytes(RELEASE_PROPERTIES_MAX_BYTES + 1)
            if (bytes.size > RELEASE_PROPERTIES_MAX_BYTES) {
                return emptyMap()
            }
            bytes.toString(Charsets.UTF_8)
        }
        val map = linkedMapOf<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
        return map
    }
}

/** Small indirection so [PolicyPluginDeclaration] reads declaratively end to end. */
private object StepProviderMetadataFactory {
    fun create(release: PluginReleaseRef) =
        dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata.create(
            plugin = PolicyPluginDeclaration.PLUGIN_REF,
            release = release,
            publisher = PolicyCheckStepDefinition.PLUGIN_ID,
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.OFFICIAL_PLUGIN,
            trust = TrustMetadata.Unverified,
        )
}

/**
 * Build-time entry point: emit the manifest document to stdout.
 *
 * A non-zero exit from missing provenance stops the build instead of shipping
 * a JAR whose manifest is absent — and a JAR without a manifest is refused at
 * runtime, so a silent skip here would produce an artifact that passes its own
 * build and fails its own admission.
 */
fun main() {
    print(PluginManifestCodec.encode(PolicyPluginDeclaration.manifest()))
}
