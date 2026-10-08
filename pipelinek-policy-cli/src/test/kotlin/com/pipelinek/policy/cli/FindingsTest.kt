package com.pipelinek.policy.cli

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `02c dedup by policy rule resource`() {
        val dup = findings + findings
        assertEquals(findings.size, dup.dedup().size)
    }

    @Test
    fun `json output is balanced`() {
        val j = FindingsEmitter.json(findings)
        assertEquals(j.count { it == '{' }, j.count { it == '}' })
    }
}
