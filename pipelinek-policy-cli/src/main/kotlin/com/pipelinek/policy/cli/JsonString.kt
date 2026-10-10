package com.pipelinek.policy.cli

/**
 * The CLI's single JSON string escaper (H4.1).
 *
 * Every JSON document the CLI emits is hand-built, in the project style that
 * keeps serialization libraries out of the CLI bucket. Hand-building JSON is
 * fine; hand-building it WITHOUT an escaper is not. The strings involved are
 * author-controlled: fixture filenames, resource paths, policy ids, rule
 * messages, decoder error text. Any of them can contain a double quote.
 *
 * So there is exactly one escaper, here, and every emitter uses it. The prior
 * state of this package had four separate escape implementations with four
 * different behaviours, one of which escaped nothing at all:
 *
 *   - [FindingsEmitter] had a correct but private `esc()`
 *   - [JsonHelp] escaped only `\` and `"`, so a raw control character (a
 *     newline in a description) produced invalid JSON
 *   - [StreamCmd] called `replace("\"", "\\\"")`, which is a NO-OP: it
 *     searches for a quote and replaces it with a quote
 *   - the commands themselves interpolated author strings with no escaping
 *
 * That last one is a real injection, not a cosmetic issue. A resource file
 * named `evil","forged":"yes.allow.json` made `test` emit a `forged` key that
 * the code never emitted, and any agent reading that JSON would believe it.
 *
 * @see str for the escaping entry point.
 */
object JsonString {

    /**
     * Escapes [value] into the *contents* of a JSON string, without the
     * surrounding quotes. Handles the two mandatory escapes, the five
     * short control escapes, and \u00XX for every remaining C0 control
     * character, which JSON forbids raw in a string.
     */
    fun escape(value: String): String = buildString(value.length + 8) {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    // U+2028/U+2029 are legal in JSON but break naive JS
                    // consumers; leave them, this is not a JS emitter.
                    append(char)
                }
            }
        }
    }

    /** [value] as a complete, quoted JSON string literal. */
    fun str(value: String): String = "\"" + escape(value) + "\""
}
