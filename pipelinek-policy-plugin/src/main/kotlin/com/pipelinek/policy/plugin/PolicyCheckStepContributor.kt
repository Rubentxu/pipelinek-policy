package com.pipelinek.policy.plugin

import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration

/**
 * ServiceLoader entry point:
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`.
 *
 * Overrides `registrations()` so the registration carries REAL provider
 * metadata from the plugin declaration (OFFICIAL_PLUGIN delivery), per the SDK
 * contributor contract. The manifest document is emitted at build time from
 * [PolicyPluginDeclaration] — one declaration, two readers.
 */
class PolicyCheckContributor : StepDefinitionContributor {
    override val id: String = PolicyCheckStepDefinition.PLUGIN_ID

    override fun definitions(): Iterable<dev.rubentxu.pipeline.v2.domain.step.StepDefinition<*, *>> =
        listOf(PolicyCheckStepDefinition)

    override fun registrations(): Iterable<StepRegistration<*, *>> = listOf(
        StepRegistration(
            PolicyCheckStepDefinition,
            PolicyPluginDeclaration.providerMetadata(),
        ),
    )

    override fun legacyPublisher(): String = PolicyCheckStepDefinition.PLUGIN_ID
}
