package com.pipelinek.policy.cli

import com.pipelinek.policy.decoder.SourceAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * REQ-M9-03/04 · Finding emitters.
 * 03a: json and jsonl carry the same findings.
 * 03b FALSIFICATION: an emitter that drops location breaks the agent UAT.
 * 04b FALSIFICATION: jsonl without remediation/fingerprint breaks the schema.
 */
class FindingsTest {

    private val findings = listOf(
        Finding.violation(
            policyId = "uat",
            ruleId = "r",
            resourceId = "doc1",
            path = "res.json",
            message = "replicas must be >= 3",
            resourceFingerprint = "fp1",
            locationPath = "spec.replicas",
            bundleDigest = "bundle-digest",
            sourceAnchor = SourceAnchor.TextSpan(3, 4, 3, 9),
            actual = "2",
            expected = ">= 3",
        ),
        Finding.refusal("bad.csv", "decode refused: malformed csv"),
    )

    @Test
    fun `03a json and jsonl agree on findings`() {
        val j = FindingsEmitter.json(findings)
        val l = FindingsEmitter.jsonl(findings).lines().filter { it.isNotBlank() }
        // both carry both findings
        assertEquals(2, Regex("\"ruleId\"").findAll(j).count())
        assertEquals(2, l.size)
        assertTrue(j.contains("\"r\""))
        assertTrue(l[1].contains("bad.csv"))
        // same fingerprints in both
        val fp = findings[0].fingerprint
        assertTrue(j.contains(fp))
        assertTrue(l[0].contains(fp))
    }

    @Test
    fun `03b falsification location is always serialized`() {
        val jsonl = FindingsEmitter.jsonl(findings)
        assertTrue(jsonl.lines()[0].contains("\"location\":{\"path\":\"res.json\"}"))
        assertTrue(jsonl.lines()[1].contains("\"location\":{\"path\":\"bad.csv\"}"))
    }

    @Test
    fun `04b falsification remediation and fingerprint always present`() {
        for (line in FindingsEmitter.jsonl(findings).lines().filter { it.isNotBlank() }) {
            assertTrue(line.contains("\"remediation\":"), "missing remediation: $line")
            assertTrue(line.contains("\"fingerprint\":"), "missing fingerprint: $line")
        }
    }

    @Test
    fun `B3 base finding schema is complete in json and jsonl without guessed governance fields`() {
        val finding = findings.first()
        val json = FindingsEmitter.json(listOf(finding))
        val jsonl = FindingsEmitter.jsonl(listOf(finding))
        val requiredFields = listOf(
            "violationId",
            "policyId",
            "ruleId",
            "bundleDigest",
            "subjectRef",
            "sourceAnchor",
            "path",
            "actual",
            "expected",
            "message",
            "remediation",
            "severity",
        )

        for (field in requiredFields) {
            assertTrue(json.contains("\"$field\""), "json missing $field: $json")
            assertTrue(jsonl.contains("\"$field\""), "jsonl missing $field: $jsonl")
        }
        for (governanceField in listOf("enforcement", "rollout", "waiverStatus")) {
            assertFalse(json.contains("\"$governanceField\""), "B3 must not guess $governanceField: $json")
            assertFalse(
                jsonl.contains("\"$governanceField\""),
                "B3 must not guess $governanceField: $jsonl",
            )
        }
        assertTrue(jsonl.contains("\"bundleDigest\":\"bundle-digest\""), jsonl)
        assertTrue(jsonl.contains("\"sourceAnchor\":{\"kind\":\"textSpan\",\"startLine\":3,"), jsonl)
        assertTrue(jsonl.contains("\"actual\":\"2\""), jsonl)
        assertTrue(jsonl.contains("\"expected\":\">= 3\""), jsonl)
    }

    @Test
    fun `02c dedup by policy rule resource`() {
        val dup = findings + findings
        assertEquals(findings.size, dup.dedup().size)
    }

    @Test
    fun `dedup preserves distinct violation locations in one resource`() {
        val anotherOccurrence = Finding.violation(
            policyId = "uat",
            ruleId = "r",
            resourceId = "doc1",
            path = "res.json",
            message = "replicas must be >= 3",
            resourceFingerprint = "fp1",
            locationPath = "spec.replicas[1]",
            bundleDigest = "bundle-digest",
        )

        assertEquals(2, listOf(findings.first(), anotherOccurrence).dedup().size)
    }

    @Test
    fun `dedup preserves distinct states for the same occurrence fingerprint`() {
        val violation = Finding.violation(
            policyId = "uat",
            ruleId = "r",
            resourceId = "doc1",
            path = "res.json",
            message = "policy rejected resource",
            resourceFingerprint = "same-resource",
            locationPath = "spec.replicas",
            bundleDigest = "bundle-digest",
        )
        val error = Finding.error(
            policyId = "uat",
            ruleId = "r",
            resourceId = "doc1",
            path = "res.json",
            message = "evaluation failed",
            resourceFingerprint = "same-resource",
            locationPath = "spec.replicas",
            bundleDigest = "bundle-digest",
        )

        assertEquals(violation.fingerprint, error.fingerprint)
        assertNotEquals(violation.state, error.state)
        assertEquals(2, listOf(violation, error).dedup().size)
    }

    @Test
    fun `agent JSON escapes control characters in finding strings`() {
        val finding = Finding.violation(
            policyId = "uat",
            ruleId = "r",
            resourceId = "doc1",
            path = "res\n.json",
            message = "first\nsecond\r\t\b\u000C${1.toChar()}",
            resourceFingerprint = "fp1",
            locationPath = "spec.replicas",
            bundleDigest = "bundle-digest",
            actual = "actual\nvalue",
        )
        val json = FindingsEmitter.json(listOf(finding))
        val jsonl = FindingsEmitter.jsonl(listOf(finding))

        assertTrue(json.contains("first\\nsecond\\r\\t\\b\\f\\u0001"), json)
        assertTrue(jsonl.contains("first\\nsecond\\r\\t\\b\\f\\u0001"), jsonl)
        assertTrue(jsonl.contains("res\\n.json"), jsonl)
        assertTrue(jsonl.contains("actual\\nvalue"), jsonl)
        assertEquals(1, jsonl.lines().size, "one finding must remain one JSONL record")
    }

    @Test
    fun `json output is balanced`() {
        val j = FindingsEmitter.json(findings)
        assertEquals(j.count { it == '{' }, j.count { it == '}' })
    }
}
