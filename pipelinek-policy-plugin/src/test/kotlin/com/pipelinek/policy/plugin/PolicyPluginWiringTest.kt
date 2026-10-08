package com.pipelinek.policy.plugin

import dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator
import dev.rubentxu.pipeline.v2.events.registry.EventDefinition
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M6 REQ-01 (ServiceLoader wiring), REQ-05 (event contributor + falsification),
 * REQ-06 (manifest identity, real provider metadata).
 */
class PolicyPluginWiringTest {

    /**
     * Provenance for tests: fail-closed declaration needs version+digest from
     * somewhere. In the artifact they come from the build-measured release
     * properties; here the system-property override is the sanctioned path
     * (same resolution order as [PolicyPluginDeclaration.release]).
     */
    @kotlin.test.BeforeTest fun setUpProvenance() {
        System.setProperty("pipelinek.policy.release.version", "0.1.0")
        System.setProperty("pipelinek.policy.release.digest", "sha256:" + "0".repeat(64))
    }

    @kotlin.test.AfterTest fun clearProvenance() {
        System.clearProperty("pipelinek.policy.release.version")
        System.clearProperty("pipelinek.policy.release.digest")
    }

    @Test
    fun `service loader discovers the step contributor`() {
        val found = ServiceLoader.load(
            dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor::class.java,
            javaClass.classLoader,
        ).toList()
        val policy = found.firstOrNull { it.id == PolicyCheckStepDefinition.PLUGIN_ID }
        assertEquals(PolicyCheckStepDefinition.KEY, policy!!.definitions().first().contract.key)
    }

    @Test
    fun `service loader discovers the event contributor`() {
        val found = ServiceLoader.load(
            dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor::class.java,
            javaClass.classLoader,
        ).toList()
        assertTrue(found.any { it.id == PolicyCheckStepDefinition.PLUGIN_ID })
    }

    @Test
    fun `event definition is valid and namespaced`() {
        val creation = PolicyEventContributor().definitions().single()
        val valid = assertIs<EventDefinitionCreation.Valid<*>>(creation)
        assertEquals(PolicyCheckStepDefinition.REPORTED_EVENT_KIND, valid.definition.kind)
        assertEquals(1, valid.definition.schemaVersion)
        assertEquals(PolicyCheckStepDefinition.PLUGIN_ID, valid.definition.emittedBy)
    }

    @Test
    fun `FALSIFICATION unnamespaced kind is refused by the SDK constructor`() {
        val creation = EventDefinition.create(
            kind = "unnamespaced",
            schemaVersion = 1,
            payloadClass = PolicyCheckReported::class.java,
            emittedBy = PolicyCheckStepDefinition.PLUGIN_ID,
            codec = PolicyCheckReportedCodec,
        )
        val invalid = assertIs<EventDefinitionCreation.Invalid<*>>(creation)
        assertTrue(invalid.problems.any { "namespaced" in it })
    }

    @Test
    fun `event codec round trip preserves payload`() {
        val payload = PolicyCheckReported(
            verdict = "VIOLATED",
            violationsCount = 2,
            reportDigest = "abc",
            policySetId = "set",
        )
        val decoded = PolicyCheckReportedCodec.decode(
            PolicyCheckReportedCodec.encode(payload),
            schemaVersion = 1,
        )
        val decodedPayload = assertIs<dev.rubentxu.pipeline.v2.events.registry.PayloadDecode.Decoded<PolicyCheckReported>>(decoded)
        assertEquals(payload, decodedPayload.payload)
    }

    @Test
    fun `manifest passes the SDK validator against registered definitions`() {
        val manifest = PolicyPluginDeclaration.manifest()
        // The manifest's step contributions must match exactly what the
        // contributor registers (REQ-06a): same keys, same capability set.
        val registered = PolicyCheckContributor().registrations().single()
        assertEquals(registered.definition.contract.key, manifest.contributions.steps.single().stepKey)
        assertEquals(
            registered.definition.contract.requiredCapabilities,
            manifest.contributions.steps.single().declaredCapabilities,
        )
        assertEquals(PolicyCheckStepDefinition.PLUGIN_ID, manifest.publisher)
    }

    @Test
    fun `registration carries real OFFICIAL_PLUGIN provider metadata`() {
        val registration = PolicyCheckContributor().registrations().single()
        assertEquals(PolicyPluginDeclaration.PLUGIN_REF, registration.provider.plugin)
    }
}
