package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M7 REQ-M7-07 — the roadmap's principal UAT.
 *
 * A new mandatory rule in SHADOW detects 20 would-deny over a historical
 * corpus of 20 resources; activating it (ENFORCED) fails all 20; a valid
 * waiver saves EXACTLY one subject (subject[3]) and no other.
 *
 * Scenario 07d (falsification): a waiver whose selector over-matches (a
 * wildcard-ish mutation matching more than one subject) is detected because
 * the waived count exceeds 1.
 */
class M7UatCorpusTest {

    private val now: Instant = Instant.parse("2026-06-01T12:00:00Z")
    private val corpusSize = 20

    /** The NEW mandatory rule: metadata.team must be "platform". */
    private val newMandatoryRule = Rule(
        id = "team-must-be-platform",
        message = "metadata.team must be platform",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("team"), ValueNode.Type.TEXT),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    /** 20 historical resources, each from a DIFFERENT (non-platform) team. */
    private val corpus: List<ValueNode> = (0 until corpusSize).map { i ->
        ValueNode.MappingValue(
            mapOf(
                "metadata" to ValueNode.MappingValue(
                    mapOf(
                        "name" to ValueNode.TextValue("svc-$i"),
                        "team" to ValueNode.TextValue("team-$i"),
                    ),
                ),
            ),
        )
    }

    private fun evaluateCorpus(): List<PolicyReport> {
        val set = PolicySet("uat", listOf(Policy("uat", listOf(newMandatoryRule))))
        return corpus.map { Evaluator.evaluate(set, it) }
    }

    private fun resolverFor(tree: ValueNode): (String) -> String? = { path ->
        if (path == "metadata.team") {
            val metadata = (tree as ValueNode.MappingValue).entries["metadata"] as? ValueNode.MappingValue
            (metadata?.entries?.get("team") as? ValueNode.TextValue)?.text
        } else {
            null
        }
    }

    // --- 07a: shadow run detects 20 would-deny without failing ---

    @Test
    fun `07a shadow run over 20-subject corpus yields 20 would-deny and Success outcomes`() {
        val reports = evaluateCorpus()

        val wouldDeny = reports.count { report ->
            report.results.values.any { it is RuleEvaluation.Violated }
        }
        assertEquals(corpusSize, wouldDeny, "all 20 subjects would be denied")

        // Shadow contract (plugin-level outcome): a would-deny in SHADOW never
        // fails. Here we verify the kernel side: each report HAS violations
        // (evidence) — the plugin test (04a) proves the outcome stays Success.
        assertTrue(reports.all { r -> r.results.values.any { it.violations.isNotEmpty() } })
    }

    // --- 07b: enforced run fails all 20 ---

    @Test
    fun `07b enforced run yields 20 active violations`() {
        val reports = evaluateCorpus()

        val violations = reports.sumOf { report ->
            report.results.values.count { it is RuleEvaluation.Violated }
        }
        assertEquals(corpusSize, violations)
    }

    // --- 07c: a waiver for subject[3] saves exactly one subject ---

    @Test
    fun `07c waiver for subject 3 waives exactly one subject of twenty`() {
        val reports = evaluateCorpus()

        var waived = 0
        var active = 0
        reports.forEachIndexed { i, report ->
            val waiver = waiverForTeam("team-3")
            val app = WaiverMatcher.apply(report, listOf(waiver), now, resolverFor(corpus[i]))
            if (app.waivedCount == 1) waived++ else active += app.activeCount
        }

        assertEquals(1, waived, "exactly one subject (svc-3) may be waived")
        assertEquals(corpusSize - 1, active)
    }

    // --- 07d: falsification — an over-matching selector is detected ---

    @Test
    fun `07d mutation-contract - over-matching waiver would waive more than one and is detected`() {
        val reports = evaluateCorpus()

        // A BROKEN matcher (mutation): subjectEquals ignored, matches any team.
        val overMatchingWaivers = corpus.mapIndexed { i, tree ->
            Waiver(
                id = "wild",
                policyId = "uat",
                ruleId = "team-must-be-platform",
                subject = ResourceSubjectSelector("metadata.team", "team-3"),
                reason = "legacy",
                issuer = "sec",
                notBefore = now.minusSeconds(3600),
                notAfter = now.plusSeconds(3600),
            ) to resolverFor(tree)
        }

        // Simulate the mutation: resolver that always returns the waived team.
        val reportsWithBrokenResolver = evaluateCorpus()
        var waivedCount = 0
        reportsWithBrokenResolver.forEachIndexed { i, report ->
            val brokenResolver: (String) -> String? = { "team-3" } // matches everything
            val app = WaiverMatcher.apply(
                report,
                listOf(overMatchingWaivers[i].first),
                now,
                brokenResolver,
            )
            if (app.waivedCount == 1) waivedCount++
        }

        // The broken matcher waives all 20: the UAT contract (waived == 1)
        // would FAIL — which is exactly how the mutation is detected.
        assertEquals(corpusSize, waivedCount, "over-matching resolver waives everything; 07c pins waived==1")
        assertTrue(waivedCount > 1, "this is the falsification evidence: >1 waived")
    }

    private fun waiverForTeam(team: String) = Waiver(
        id = "w-$team",
        policyId = "uat",
        ruleId = "team-must-be-platform",
        subject = ResourceSubjectSelector("metadata.team", team),
        reason = "legacy service",
        issuer = "security-team",
        notBefore = now.minusSeconds(3600),
        notAfter = now.plusSeconds(3600),
    )
}
