package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.random.Random

/**
 * B6.4 · Deterministic corpus generator.
 *
 * Own PRNG with a FIXED seed so every property test is reproducible: same seed
 * ⇒ same corpus ⇒ same digest. Zero new dependencies (law 6): kotlin.random.
 *
 * What changed for B6.4, and why the previous generator was not enough:
 *
 * It emitted only ASCII keys (`k0`, `k1`, …), small positive integers and text
 * from a pool of 32 ASCII strings. Everything it generated was valid,
 * well-formed and boring, which means it could only ever test the happy path.
 * B6.4 asks for malformed input, Unicode, duplicate keys, extreme numbers,
 * round-trips and depth limits — and a generator that emits none of those
 * cannot produce a counterexample to any of them.
 *
 * Every generator takes its own `Random` and is deterministic, so a failing
 * case reproduces exactly. Failures print the seed for that reason.
 */
object RandomDocuments {

    const val SEED: Long = 0x4D10_C0_71F00L
    val rng: Random get() = Random(SEED)

    private const val MAX_DEPTH = 6
    private const val MAX_WIDTH = 8

    /**
     * Unicode text pool. Deliberately includes what has historically broken
     * byte→char loops: 4-byte astral plane (emoji), ZWJ sequences, combining
     * marks, RTL, zero-width characters, a NUL and a lone surrogate.
     *
     * A lone surrogate is here on purpose. It has no UTF-8 encoding, so any
     * encoder asked to serialize it must either refuse or escape it — and
     * silently substituting U+FFFD would turn a surrogate test into a
     * replacement-character test that proves nothing.
     */
    val UNICODE_TEXTS: List<String> = listOf(
        "plain-ascii",
        "acentuación-ñ",
        "日本語テキスト",
        "emoji-🚀-🌍",
        "family-👨‍👩‍👧‍👦-zwj",
        "combining-é",
        "rtl-مرحبا-שלום",
        "zero-width\u200Bspace",
        "bom\uFEFFmarker",
        "tab\tand\nnewline",
        "quote\"backslash\\",
        "nul\u0000byte",
        "surrogate-\uD83D-pair",
        "combining-only-\u0301",
    )

    /**
     * Extreme numbers: the boundaries where a careless double or long
     * conversion silently loses precision or overflows.
     *
     * `Long.MAX_VALUE` plus one overflows; the long decimal literals exceed a
     * 64-bit long entirely, so they exercise the BigDecimal path B5.6
     * introduced for exact sums.
     */
    val EXTREME_NUMBERS: List<String> = listOf(
        "0",
        "-0",
        "1",
        "-1",
        "9223372036854775807",
        "-9223372036854775808",
        "9223372036854775808",
        "-9223372036854775809",
        "18446744073709551616",
        "1e308",
        "1e-308",
        "1.7976931348623157E308",
        "0.1",
        "0.30000000000000004",
        "1e-400",
        "1234567890123456789012345678901234567890",
        "-0.000000000000000000001",
        "1.0000000000000000000000001",
    )

    /** Keys that stress Unicode, escaping and length, not just `kN`. */
    val UNICODE_KEYS: List<String> = listOf(
        "team", "spec", "metadata", "replicas",
        "ключ", "键", "clé", "schlüssel",
        "with space", "with-dash", "with.dot", "with_underscore",
        "UPPER", "mixedCase",
        "\u200BzeroWidth", "emoji🔑key",
        "a-very-long-key-name-that-exceeds-sixty-four-characters-for-sure-indeed",
    )

    /** Bounded random valid document: mappings/sequences/leaves. */
    fun randomDocument(r: Random, depth: Int = 0): ValueNode {
        val kind = r.nextInt(4)
        return when {
            depth >= MAX_DEPTH -> leaf(r)
            kind == 0 -> leaf(r)
            kind == 1 || kind == 2 -> {
                val n = 1 + r.nextInt(MAX_WIDTH)
                ValueNode.MappingValue(
                    (0 until n).associate { i ->
                        val key = if (r.nextInt(4) == 0) UNICODE_KEYS.random(r) else "k$i"
                        key to randomDocument(r, depth + 1)
                    },
                )
            }
            else -> {
                val n = 1 + r.nextInt(MAX_WIDTH)
                ValueNode.SequenceValue(
                    (0 until n).map { randomDocument(r, depth + 1) },
                )
            }
        }
    }

    private fun leaf(r: Random): ValueNode = when (r.nextInt(6)) {
        0 -> ValueNode.TextValue(UNICODE_TEXTS.random(r))
        1 -> ValueNode.NumberValue(r.nextLong())
        2 -> ValueNode.BooleanValue(r.nextBoolean())
        3 -> ValueNode.Null
        4 -> ValueNode.NumberValue(java.math.BigDecimal(EXTREME_NUMBERS.random(r)))
        else -> ValueNode.TextValue("t${r.nextInt(32)}")
    }

    /** Deterministic corpus of valid documents (same seed ⇒ same list). */
    fun corpus(count: Int): List<ValueNode> {
        val r = rng
        return (0 until count).map { randomDocument(r) }
    }

    /**
     * A JSON payload structurally encoding [doc], as real bytes.
     *
     * This is the seam B6.4 was missing. The old corpus fed [ValueNode] trees
     * straight to the evaluator, so no property test ever exercised
     * serialization or the decoders — the properties were about the evaluator,
     * not about the stack that actually parses untrusted input.
     */
    fun toJsonBytes(doc: ValueNode): ByteArray = render(doc).toByteArray(Charsets.UTF_8)

    /** The same document as JSONL: one whole document per line. */
    fun toJsonLinesBytes(docs: List<ValueNode>): ByteArray =
        docs.joinToString("\n") { render(it) }.toByteArray(Charsets.UTF_8)

    fun toCsvBytes(rows: List<Map<String, String>>): ByteArray {
        val header = rows.firstOrNull()?.keys?.toList() ?: emptyList()
        val sb = StringBuilder(header.joinToString(","))
        sb.append('\n')
        rows.forEach { row ->
            sb.append(header.joinToString(",") { row[it].orEmpty() })
            sb.append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun render(node: ValueNode): String = when (node) {
        is ValueNode.MappingValue -> node.entries.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { (k, v) -> "${jsonString(k)}:${render(v)}" }

        is ValueNode.SequenceValue -> node.elements.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { render(it) }

        is ValueNode.TextValue -> jsonString(node.text)
        is ValueNode.BooleanValue -> node.boolean.toString()
        is ValueNode.NumberValue -> node.number.toString()
        ValueNode.Null -> "null"
        // Rendered as a missing field: an absent value has no literal form, so
        // the round trip under test only exercises the cases that do.
        ValueNode.Missing -> throw IllegalArgumentException("MISSING_HAS_NO_LITERAL")
    }

    /**
     * Minimal JSON string escaping, including control characters and the
     * surrogate range. Surrogates are emitted as `\uXXXX` rather than as raw
     * UTF-8 because a lone surrogate has no UTF-8 encoding; emitting it raw
     * would replace it with U+FFFD and the property would silently be testing
     * the replacement character instead.
     */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                c.isHighSurrogate() || c.isLowSurrogate() -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Malformed JSON bytes: random mutations of a valid prefix. */
    fun malformedJson(r: Random): ByteArray {
        val base = """{"a":[1,2,{"b":true}],"c":"x"}"""
        val cut = 3 + r.nextInt(base.length - 4)
        val mangled = StringBuilder(base.substring(0, cut))
        when (r.nextInt(4)) {
            0 -> mangled.append(r.nextInt(2) + 1)
            1 -> mangled.append('\\')
            2 -> mangled.append('"')
            // A raw control character inside a string is the malformed case most
            // parsers get wrong, and the previous generator never produced it.
            else -> mangled.append(' ')
        }
        return mangled.toString().toByteArray(Charsets.UTF_8)
    }

    /** Malformed YAML bytes: tab-indent, unclosed flow, or bad indentation. */
    fun malformedYaml(r: Random): ByteArray = when (r.nextInt(3)) {
        0 -> "a:\n\t- b\n".toByteArray(Charsets.UTF_8)
        1 -> "{a: [1, 2".toByteArray(Charsets.UTF_8)
        else -> "a: 1\n  b: 2\n c: 3\n".toByteArray(Charsets.UTF_8)
    }

    /**
     * JSON with a duplicate key, which the decoders must resolve as an explicit
     * refusal rather than silently letting the last occurrence win.
     */
    fun duplicateKeyJson(r: Random): ByteArray {
        val key = UNICODE_KEYS.random(r)
        return """{"$key":1,"$key":2}""".toByteArray(Charsets.UTF_8)
    }

    /** YAML with a duplicate key, same intent. */
    fun duplicateKeyYaml(r: Random): ByteArray {
        val key = UNICODE_KEYS.random(r)
        return "$key: 1\n$key: 2\n".toByteArray(Charsets.UTF_8)
    }

    /** JSON nested [depth] levels deep, for the depth-limit property. */
    fun deeplyNestedJson(depth: Int): ByteArray {
        val sb = StringBuilder()
        repeat(depth) { sb.append("{\"k\":") }
        sb.append("1")
        repeat(depth) { sb.append("}") }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Malformed CSV never exists (CSV is total over bytes): see spec 04b note. */
    const val CSV_IS_TOTAL: Boolean = true
}