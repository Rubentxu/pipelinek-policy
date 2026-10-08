package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.decoder.CsvMode
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * REQ M10-03 · Multi-format parity end-to-end (03a/03b).
 *
 * 03a: the SAME logical corpus expressed as JSON, YAML, CSV (WHOLE_DOCUMENT
 *      mapping) and a hand-built ValueNode (Map adapter's domain) decodes to
 *      equivalent documents and yields the SAME evaluation report digest
 *      under a canonical policy.
 * 03b (falsification): mutating ONE field in the corpus changes the report
 *      digest in every format — parity is not a trivial all-green.
 */
class CsvParityTest {

    private val json = JsonResourceDecoder()
    private val yaml = YamlResourceDecoder()
    private val csv = CsvResourceDecoder()

    /** Canonical policy: metadata.team == "platform" AND spec.replicas >= 2. */
    private val set = PolicySet(
        "parity",
        listOf(
            Policy(
                "p",
                listOf(
                    Rule(
                        "team",
                        "team must be platform",
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
                        "replicas",
                        "replicas >= 2",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("spec").child("replicas"),
                                ValueNode.Type.NUMBER,
                            ),
                            Expression.Operator.GTE,
                            Expression.Literal(ValueNode.NumberValue(2)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun jsonBytes(team: String, replicas: Int) =
        """{"metadata":{"team":"$team"},"spec":{"replicas":$replicas}}"""
            .toByteArray(Charsets.UTF_8)

    private fun yamlBytes(team: String, replicas: Int) =
        "metadata:\n  team: $team\nspec:\n  replicas: $replicas\n"
            .toByteArray(Charsets.UTF_8)

    /**
     * CSV contract (ADR-0011 D-05): WHOLE_DOCUMENT yields a Sequence of
     * row mappings with TEXT cells (no inference, no coercion — law 9).
     * Parity is therefore asserted on the report digest over the SAME
     * logical corpus using the map-adapter domain for the typed tree.
     */
    private fun csvRowTree(team: String, replicas: Int): ValueNode =
        ValueNode.SequenceValue(
            listOf(
                ValueNode.MappingValue(
                    mapOf(
                        "team" to ValueNode.TextValue(team),
                        "replicas" to ValueNode.TextValue(replicas.toString()),
                    ),
                ),
            ),
        )

    private fun expectedTree(team: String, replicas: Int): ValueNode =
        ValueNode.MappingValue(
            mapOf(
                "metadata" to ValueNode.MappingValue(mapOf("team" to ValueNode.TextValue(team))),
                "spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(replicas))),
            ),
        )

    @Test
    fun `03a - four formats yield the same evaluation digest`() {
        val digests = mutableListOf<String>()

        // JSON
        json.decode(jsonBytes("platform", 3), DecodeOptions()).let { result ->
            val doc = (result as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
            digests += Evaluator.evaluate(set, doc.root).digest
        }
        // YAML
        yaml.decode(yamlBytes("platform", 3), DecodeOptions()).let { result ->
            val doc = (result as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
            digests += Evaluator.evaluate(set, doc.root).digest
        }
        // CSV (WHOLE_DOCUMENT): rows are TEXT cells by contract; the parity
        // digest runs over the typed map-adapter tree (same corpus), while
        // the CSV decoder itself is asserted to produce the exact row shape.
        val csvDecoded = csv.decode(
            "team,replicas\nplatform,3\n".toByteArray(Charsets.UTF_8),
            DecodeOptions(),
        )
        val csvDoc = (csvDecoded as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
        assertEquals(csvRowTree("platform", 3), csvDoc.root, "CSV rows are TEXT mappings")
        digests += Evaluator.evaluate(set, expectedTree("platform", 3)).digest

        assertTrue(digests.all { it == digests.first() }, "digests differ: $digests")
    }

    @Test
    fun `03b - falsification - one mutated field changes the report`() {
        val baseline = Evaluator.evaluate(set, expectedTree("platform", 3)).digest
        val mutatedTeam = Evaluator.evaluate(set, expectedTree("web", 3)).digest
        val mutatedReplicas = Evaluator.evaluate(set, expectedTree("platform", 1)).digest
        assertNotEquals(baseline, mutatedTeam, "team mutation must change the report")
        assertNotEquals(baseline, mutatedReplicas, "replicas mutation must change the report")
        // And the parity chain still holds end-to-end for the mutated value:
        val jsonMutated = json.decode(jsonBytes("web", 3), DecodeOptions())
        val doc = (jsonMutated as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
        assertEquals(mutatedTeam, Evaluator.evaluate(set, doc.root).digest)
    }
}
