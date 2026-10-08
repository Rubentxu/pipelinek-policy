package com.pipelinek.policy.cli

/**
 * M9 · minimal argv parser: positional args + `--flag value` pairs.
 * No external dependency; unknown flags are kept verbatim so each
 * command can refuse them with its own usage error.
 */
data class CliArgs(
    val positionals: List<String>,
    val flags: Map<String, String>,
    val booleanFlags: Set<String>,
) {
    fun flag(name: String): String? = flags[name]
    fun has(name: String): Boolean = flags.containsKey(name) || name in booleanFlags

    companion object {
        fun parse(args: List<String>): CliArgs {
            val positionals = mutableListOf<String>()
            val flags = linkedMapOf<String, String>()
            val booleans = mutableSetOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--json-help" || a == "--help" -> booleans += a.removePrefix("--")
                    a.startsWith("--") -> {
                        val next = args.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) {
                            flags[a.removePrefix("--")] = next
                            i++
                        } else {
                            booleans += a.removePrefix("--")
                        }
                    }
                    else -> positionals += a
                }
                i++
            }
            return CliArgs(positionals, flags, booleans)
        }
    }
}
