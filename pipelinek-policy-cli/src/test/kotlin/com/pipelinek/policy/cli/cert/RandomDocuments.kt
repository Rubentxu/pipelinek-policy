package com.pipelinek.policy.cli.cert

import kotlin.random.Random

/**
 * REQ M10-04 · Deterministic corpus generator (design D3).
 *
 * Own PRNG with a FIXED seed so every "property test" is reproducible:
 * same seed ⇒ same corpus ⇒ same digest (asserted by 04c). Zero new
 * dependencies (law 6): kotlin.random.Random only.
 *
 * Emits:
 *  - valid random [ValueNode] documents (bounded depth/width);
 *  - malformed bytes per format for fuzz refusals (04b).
 */
object RandomDocuments {

    const val SEED: Long = 0x4D10_C0_71F00L
    val rng: Random get() = Random(SEED)

    private const val MAX_DEPTH = 6
    private const val MAX_WIDTH = 8
    private const val TEXT_POOL = 32

    /** Bounded random valid document: mappings/sequences/leaves. */
    fun randomDocument(r: Random, depth: Int = 0): com.pipelinek.policy.kernel.value.ValueNode {
        val kind = r.nextInt(4)
        return when {
            depth >= MAX_DEPTH -> leaf(r)
            kind == 0 -> leaf(r)
            kind == 1 || kind == 2 -> {
                val n = 1 + r.nextInt(MAX_WIDTH)
                com.pipelinek.policy.kernel.value.ValueNode.MappingValue(
                    (0 until n).associate { "k$it" to randomDocument(r, depth + 1) },
                )
            }
            else -> {
                val n = 1 + r.nextInt(MAX_WIDTH)
                com.pipelinek.policy.kernel.value.ValueNode.SequenceValue(
                    (0 until n).map { randomDocument(r, depth + 1) },
                )
            }
        }
    }

    private fun leaf(r: Random): com.pipelinek.policy.kernel.value.ValueNode = when (r.nextInt(4)) {
        0 -> com.pipelinek.policy.kernel.value.ValueNode.TextValue("t${r.nextInt(TEXT_POOL)}")
        1 -> com.pipelinek.policy.kernel.value.ValueNode.NumberValue(r.nextInt(1000))
        2 -> com.pipelinek.policy.kernel.value.ValueNode.BooleanValue(r.nextBoolean())
        else -> com.pipelinek.policy.kernel.value.ValueNode.Null
    }

    /** Deterministic corpus of valid documents (same seed ⇒ same list). */
    fun corpus(count: Int): List<com.pipelinek.policy.kernel.value.ValueNode> {
        val r = rng
        return (0 until count).map { randomDocument(r) }
    }

    /** Malformed JSON bytes: random mutations of a valid prefix. */
    fun malformedJson(r: Random): ByteArray {
        val base = """{"a":[1,2,{"b":true}],"c":"x"}"""
        val cut = 3 + r.nextInt(base.length - 4)
        val mangled = StringBuilder(base.substring(0, cut))
        when (r.nextInt(3)) {
            0 -> mangled.append(r.nextInt(2) + 1) // {"a":[1,2,{"b":tru... + 2
            1 -> mangled.append('\\')
            else -> mangled.append('"')
        }
        return mangled.toString().toByteArray(Charsets.UTF_8)
    }

    /** Malformed YAML bytes: tab-indent or unclosed flow is a refusal. */
    fun malformedYaml(r: Random): ByteArray = when (r.nextInt(2)) {
        0 -> "a:\n\t- b\n".toByteArray(Charsets.UTF_8) // tab indentation
        else -> "{a: [1, 2".toByteArray(Charsets.UTF_8) // unclosed flow
    }

    /** Malformed CSV never exists (CSV is total over bytes): see spec 04b note. */
    const val CSV_IS_TOTAL: Boolean = true
}
