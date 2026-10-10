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
    /**
     * Every occurrence of each `--flag`, in argv order.
     *
     * Added for H1.2: a `Map` keeps only the LAST value of a repeated flag, so
     * `--layer` could carry exactly one layer. Layer composition is inherently
     * N-ary (org over platform over project), and a flag that silently drops
     * every layer but the last would compose the wrong policy set while looking
     * like it worked. [flag] keeps returning a single value so every existing
     * command is unaffected.
     */
    val multiFlags: Map<String, List<String>> = emptyMap(),
) {
    fun flag(name: String): String? = flags[name]

    /** Every value given for [name], in argv order. Empty when never supplied. */
    fun flagValues(name: String): List<String> = multiFlags[name].orEmpty()

    fun has(name: String): Boolean = flags.containsKey(name) || name in booleanFlags

    companion object {
        fun parse(args: List<String>): CliArgs {
            val positionals = mutableListOf<String>()
            val flags = linkedMapOf<String, String>()
            val booleans = mutableSetOf<String>()
            val multi = linkedMapOf<String, MutableList<String>>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--json-help" || a == "--help" -> booleans += a.removePrefix("--")
                    a.startsWith("--") -> {
                        val next = args.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) {
                            val key = a.removePrefix("--")
                            flags[key] = next
                            multi.getOrPut(key) { mutableListOf() } += next
                            i++
                        } else {
                            booleans += a.removePrefix("--")
                        }
                    }
                    else -> positionals += a
                }
                i++
            }
            return CliArgs(positionals, flags, booleans, multi)
        }
    }
}
