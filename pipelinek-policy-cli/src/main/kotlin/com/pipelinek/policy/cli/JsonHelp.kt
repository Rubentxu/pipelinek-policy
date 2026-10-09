package com.pipelinek.policy.cli

/**
 * M9 · machine-readable help derived from [CommandRegistry] (REQ-M9-05).
 * Hand-written JSON (same style as the core's CanonicalPolicyJson): the
 * CLI adds no serialization dependency.
 */
object JsonHelp {

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    fun rootJson(): String {
        val cmds = CommandRegistry.commands.joinToString(",\n") { c ->
            "    {\"name\": \"${c.name}\", \"description\": \"${c.description.jsonEscape()}\", " +
                "\"flags\": [${c.flags.joinToString(",") { "\"$it\"" }}]}"
        }
        return "{\n" +
            "  \"binary\": \"pipelinek-policy\",\n" +
            "  \"exit_codes\": {" +
            "\"0\": \"success, no violations\", " +
            "\"1\": \"violations present\", " +
            "\"2\": \"usage error, refusal, or decode error\", " +
            "\"3\": \"typed evaluator/engine error or unexpected internal error\"},\n" +
            "  \"commands\": [\n$cmds\n  ]\n" +
            "}"
    }

    fun commandJson(spec: CommandSpec): String =
        "{\n" +
            "  \"command\": \"${spec.name}\",\n" +
            "  \"description\": \"${spec.description.jsonEscape()}\",\n" +
            "  \"flags\": [${spec.flags.joinToString(",") { "\"$it\"" }}],\n" +
            "  \"exit_codes\": {\"0\": \"success\", \"1\": \"violations\", " +
            "\"2\": \"usage/refusal/decode error\", " +
            "\"3\": \"typed evaluator/engine error or internal error\"}\n" +
            "}"
}
