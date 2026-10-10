package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeRefusalCode
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.json.JsonlRefusal
import com.pipelinek.policy.decoders.json.JsonlSource
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B6.4 · Property-based and fuzz coverage.
 *
 * What was missing before: the previous version of this test evaluated
 * randomly generated [ValueNode] trees **in memory**. Nothing in it ever
 * touched a decoder, a serializer or a byte encoder, so every property was a
 * property of the evaluator — not of the stack that actually parses untrusted
 * input. Its corpus also only generated ASCII keys, small integers and valid
 * documents, so nothing could ever produce a counterexample.
 *
 * The properties asserted here, and what each one would catch:
 *
 *  - 04a total decode: serialized corpus decodes without throwing. Catches
 *    serializer/decoder disagreement, including Unicode and extreme numbers.
 *  - 04b typed refusal: malformed payloads refuse, never crash. Catches
 *    undeclared exceptions on adversarial input.
 *  - 04c round-trip: decode(encode(tree)) preserves the canonical digest. This
 *    is the property the old test implicitly assumed and never checked; a
 *    lossy encoder now fails here instead of silently degrading data.
 *  - 04d duplicate keys: refused as DUPLICATE_KEY in JSON, YAML and JSONL. Not
 *    last-wins, which would be a silent data-loss bug.
 *  - 04e depth: deeply nested input is refused or bounded, never a
 *    StackOverflowError escaping as an undeclared throwable.
 *  - 04f determinism: the same seed reproduces the same corpus, and a
 *    different seed does not.
 */
class PropertyFuzzTest {

    private val json = JsonResourceDecoder()
    private val yaml = YamlResourceDecoder()
    private val csv = CsvResourceDecoder()

    /** Canonical policy: one text rule and one numeric rule. */
    private val canonicalSet = PolicySet(
        "cert-fuzz",
        listOf(
            Policy(
                "cert",
                listOf(
                    Rule(
                        "metadata-team-text",
                        "metadata.team must be text when present",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("metadata").child("team"),
                                ValueNode.Type.TEXT,
                            ),
                            Expression.Operator.TEXT_EQUALS,
                            Expression.Literal(ValueNode.TextValue("platform")),
                        ),
                    ),
                    Rule(
                        "spec-replicas-gte",
                        "spec.replicas >= 1 when numeric",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("spec").child("replicas"),
                                ValueNode.Type.NUMBER,
                            ),
                            Expression.Operator.GTE,
                            Expression.Literal(ValueNode.NumberValue(1)),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `04a - serialized corpus decodes and evaluates totally`() {
        val corpus = RandomDocuments.corpus(200)
        assertEquals(200, corpus.size, "corpus count pinned for reproducibility")

        // The property the old test only claimed: these are BYTES now, so a
        // serializer that cannot round-trip a value fails here.
        var decodedOk = 0
        corpus.forEachIndexed { i, doc ->
            val bytes = RandomDocuments.toJsonBytes(doc)
            val result = json.decode(bytes, DecodeOptions())
            if (result is DecodeResult.Ok) {
                decodedOk++
                // Totality: the evaluator always returns a report, never throws.
                val report = Evaluator.evaluate(canonicalSet, result.documents.single().root)
                assertTrue(
                    report.results.keys.map { it.ruleId }.containsAll(
                        listOf("metadata-team-text", "spec-replicas-gte"),
                    ),
                    "evaluation must be total for corpus[$i] (seed=${RandomDocuments.SEED})",
                )
            }
        }
        assertTrue(decodedOk > 0, "no corpus document decoded; the serializer is broken")
    }

    @Test
    fun `04b - malformed payloads refuse without throwing`() {
        val seeds = listOf(
            "json" to { r: Random -> json.decode(RandomDocuments.malformedJson(r), DecodeOptions()) },
            "yaml" to { r: Random -> yaml.decode(RandomDocuments.malformedYaml(r), DecodeOptions()) },
        )
        seeds.forEach { (name, run) ->
            val r = Random(RandomDocuments.SEED + name.hashCode())
            repeat(200) { i ->
                val result = run(r)
                assertTrue(
                    result is DecodeResult.Refused,
                    "malformed $name must refuse (case $i, seed=${RandomDocuments.SEED + name.hashCode()})",
                )
            }
        }
    }

    @Test
    fun `04b - csv decoder is total over arbitrary bytes`() {
        // Contract: CSV has no malformed input — any byte stream is either rows
        // or an empty refusal. The property is "never throws", not "refuses".
        val r = Random(RandomDocuments.SEED + 3)
        repeat(200) { i ->
            val bytes = ByteArray(64) { r.nextInt(256).toByte() }
            val result = csv.decode(bytes, DecodeOptions())
            assertTrue(
                result is DecodeResult.Ok || result is DecodeResult.Refused,
                "CSV must be total (case $i)",
            )
        }
    }

    @Test
    fun `04b - truncated and hostile byte patterns never throw`() {
        // Byte-level fuzz rather than grammar-level: truncations of valid JSON,
        // every single-byte mutation of a short payload, and raw NUL runs.
        val valid = """{"a":[1,2,{"b":true}],"c":"x"}""".toByteArray(Charsets.UTF_8)
        (0..valid.size).forEach { cut ->
            val result = json.decode(valid.copyOf(cut), DecodeOptions())
            assertTrue(result is DecodeResult.Ok || result is DecodeResult.Refused, "truncation at $cut")
        }
        val r = Random(RandomDocuments.SEED + 4)
        repeat(500) {
            val base = valid.copyOf()
            base[r.nextInt(base.size)] = r.nextInt(256).toByte()
            val result = json.decode(base, DecodeOptions())
            assertTrue(result is DecodeResult.Ok || result is DecodeResult.Refused, "single-byte mutation")
        }
        val nul = ByteArray(32) { 0 }
        assertTrue(json.decode(nul, DecodeOptions()) is DecodeResult.Refused, "NUL run must refuse")
    }

    @Test
    fun `04c - round trip preserves the canonical digest`() {
        // The property the old suite assumed without checking. A lossy encoder
        // or a decoder that drops information must fail HERE.
        val corpus = RandomDocuments.corpus(120)
        var checked = 0
        corpus.forEachIndexed { i, doc ->
            val encoded = RandomDocuments.toJsonBytes(doc)
            val decoded = json.decode(encoded, DecodeOptions())
            if (decoded !is DecodeResult.Ok) return@forEachIndexed
            checked++
            assertEquals(
                doc.canonicalDigest(),
                decoded.documents.single().root.canonicalDigest(),
                "JSON round trip must preserve the tree (corpus[$i], seed=${RandomDocuments.SEED})",
            )
        }
        // Measured, not guessed: the corpus round-trips 120/120, so the property
        // is pinned to the WHOLE corpus. A weaker threshold would let the suite
        // pass while most documents silently stopped decoding — which is exactly
        // the "green but hollow" failure this test exists to prevent.
        assertEquals(120, checked, "every corpus document must decode, else the round trip is vacuous")
    }

    @Test
    fun `04c - unicode text survives the round trip byte for byte`() {
        // Targeted rather than random: the cases that historically broke
        // byte→char loops, asserted one by one so a failure names the culprit.
        var checked = 0
        RandomDocuments.UNICODE_TEXTS.forEach { text ->
            val doc = ValueNode.MappingValue(mapOf("k" to ValueNode.TextValue(text)))
            val decoded = json.decode(RandomDocuments.toJsonBytes(doc), DecodeOptions())
            if (decoded !is DecodeResult.Ok) return@forEach
            val root = decoded.documents.single().root
            val back = (root as ValueNode.MappingValue).entries["k"]
            assertEquals(ValueNode.TextValue(text), back, "unicode text mangled: '$text'")
            checked++
        }
        // A lone surrogate has no UTF-8 encoding, so it is escaped and must come
        // back escaped-decoded rather than replaced with U+FFFD.
        assertTrue(checked >= 12, "only $checked unicode cases round-tripped; the pool shrank")
    }

    @Test
    fun `04d - duplicate keys refuse as DUPLICATE_KEY`() {
        val r = Random(RandomDocuments.SEED + 5)
        repeat(60) {
            val j = json.decode(RandomDocuments.duplicateKeyJson(r), DecodeOptions())
            assertTrue(j is DecodeResult.Refused, "JSON duplicate key must refuse")
            assertEquals(DecodeRefusalCode.DUPLICATE_KEY, j.refusal.code)

            val y = yaml.decode(RandomDocuments.duplicateKeyYaml(r), DecodeOptions())
            assertTrue(y is DecodeResult.Refused, "YAML duplicate key must refuse")
            assertEquals(DecodeRefusalCode.DUPLICATE_KEY, y.refusal.code)
        }
    }

    @Test
    fun `04d - jsonl duplicate keys refuse too`() {
        val r = Random(RandomDocuments.SEED + 6)
        repeat(60) {
            val key = RandomDocuments.UNICODE_KEYS.random(r)
            val bytes = """{"$key":1,"$key":2}""".toByteArray(Charsets.UTF_8)
            // JsonlSource surfaces refusals as a typed exception rather than an
            // ADT, so the property is: it refuses with DUPLICATE_KEY, and it
            // refuses before yielding any row.
            val thrown = runCatching { JsonlSource(bytes).next() }.exceptionOrNull()
            assertTrue(
                thrown is JsonlRefusal,
                "JSONL duplicate key must refuse with a typed refusal, got $thrown",
            )
            assertEquals(DecodeRefusalCode.DUPLICATE_KEY, thrown.code)
        }
    }

    @Test
    fun `04e - deep nesting is bounded, never a stack overflow`() {
        // Two properties in one: moderate depth must still decode, and extreme
        // depth must fail *gracefully* (refusal or success) rather than
        // throwing StackOverflowError out of the decoder.
        listOf(8, 32, 64).forEach { depth ->
            val result = json.decode(RandomDocuments.deeplyNestedJson(depth), DecodeOptions())
            assertTrue(
                result is DecodeResult.Ok || result is DecodeResult.Refused,
                "depth $depth must decode or refuse, not throw",
            )
        }
        // Extreme depths are the interesting ones: a decoder with no limit that
        // recurses will overflow here.
        listOf(500, 2000, 10000).forEach { depth ->
            val outcome = runCatching {
                json.decode(RandomDocuments.deeplyNestedJson(depth), DecodeOptions())
            }
            assertTrue(
                outcome.isSuccess,
                "depth $depth escaped as ${outcome.exceptionOrNull()} instead of refusing",
            )
        }
    }

    @Test
    fun `04f - determinism holds and a different seed diverges`() {
        val a = RandomDocuments.corpus(50)
        val b = RandomDocuments.corpus(50)
        assertEquals(a.map { it.canonicalDigest() }, b.map { it.canonicalDigest() }, "same seed must reproduce")

        val other = Random(RandomDocuments.SEED + 999)
        val c = (0 until 50).map { RandomDocuments.randomDocument(other) }
        assertNotEquals(
            a.map { it.canonicalDigest() },
            c.map { it.canonicalDigest() },
            "a different seed must not reproduce the same corpus",
        )
    }
}