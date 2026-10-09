package com.pipelinek.policy.cli

/**
 * M9 · single source of truth for the command surface (REQ-M9-01/05).
 * Dispatch AND --json-help derive from this registry, so they cannot
 * diverge by construction.
 */
data class CommandSpec(
    val name: String,
    val description: String,
    val flags: List<String>,
)

object CommandRegistry {
    val commands: List<CommandSpec> = listOf(
        CommandSpec("compile", "Compile a policy source into a reproducible bundle", listOf("policy", "out")),
        CommandSpec("check", "Evaluate resources against a policy bundle", listOf("policy", "format")),
        CommandSpec("test", "Run allow/deny fixtures against a policy bundle", listOf("policy", "fixtures", "format")),
        CommandSpec("diff", "Diff two bundles (optionally over a corpus)", listOf("a", "b", "corpus")),
        CommandSpec("explain", "Explain a rule evaluation", listOf("policy", "rule", "resource")),
        CommandSpec("inspect", "Dump the canonical IR of a bundle", listOf("policy")),
        CommandSpec("shape", "Structural summary of a bundle", listOf("policy")),
        CommandSpec("bundle", "Bundle operations (verify)", listOf("verify")),
        CommandSpec("stream", "Stream a dataset through a policy bundle (csv/jsonl)", listOf("policy", "row-budget", "format-out")),
    )

    fun byName(name: String): CommandSpec? = commands.firstOrNull { it.name == name }
    val names: List<String> get() = commands.map { it.name }
}
