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
            "\"1\": \"usage or invocation error\", " +
            "\"2\": \"policy violation\", " +
            "\"3\": \"evaluation or configuration error\", " +
            "\"4\": \"bundle or resource admission error\", " +
            "\"5\": \"compiler error\"},\n" +
            "  \"commands\": [\n$cmds\n  ]\n" +
            "}"
    }

    fun commandJson(spec: CommandSpec): String =
        "{\n" +
            "  \"command\": \"${spec.name}\",\n" +
            "  \"description\": \"${spec.description.jsonEscape()}\",\n" +
            "  \"flags\": [${spec.flags.joinToString(",") { "\"$it\"" }}],\n" +
            "  \"exit_codes\": {\"0\": \"success or permit\", " +
            "\"1\": \"usage or invocation error\", " +
            "\"2\": \"policy violation\", " +
            "\"3\": \"evaluation or configuration error\", " +
            "\"4\": \"bundle or resource admission error\", " +
            "\"5\": \"compiler error\"}\n" +
            "}"
}
