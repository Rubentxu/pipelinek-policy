package com.pipelinek.policy.cli

import com.pipelinek.policy.kernel.policy.ViolationFingerprint

/**
 * M9 · the actionable result contract (REQ-M9-04). Every finding carries
 * policyId/ruleId/resourceId/severity/location/remediation/fingerprint —
 * an agent never parses human text.
 */
data class Location(
    val path: String,
    val line: Int? = null,
    val column: Int? = null,
) {
    fun render(): String = when {
        line != null && column != null -> "$path:$line:$column"
        line != null -> "$path:$line"
        else -> path
    }
}

enum class FindingState { PASS, VIOLATION, WAIVED, REFUSAL, ERROR }

data class Finding(
    val policyId: String,
    val ruleId: String,
    val resourceId: String,
    val severity: String,
    val state: FindingState,
    val location: Location?,
    val remediation: String,
    val fingerprint: String,
) {
    companion object {
        fun violation(
            policyId: String,
            ruleId: String,
            resourceId: String,
            path: String,
            message: String,
            resourceFingerprint: String,
            locationPath: String,
        ): Finding = actionableFailure(
            state = FindingState.VIOLATION,
            policyId = policyId,
            ruleId = ruleId,
            resourceId = resourceId,
            path = path,
            message = message,
            resourceFingerprint = resourceFingerprint,
            locationPath = locationPath,
        )

        fun error(
            policyId: String,
            ruleId: String,
            resourceId: String,
            path: String,
            message: String,
            resourceFingerprint: String,
            locationPath: String,
        ): Finding = actionableFailure(
            state = FindingState.ERROR,
            policyId = policyId,
            ruleId = ruleId,
            resourceId = resourceId,
            path = path,
            message = message,
            resourceFingerprint = resourceFingerprint,
            locationPath = locationPath,
        )

        private fun actionableFailure(
            state: FindingState,
            policyId: String,
            ruleId: String,
            resourceId: String,
            path: String,
            message: String,
            resourceFingerprint: String,
            locationPath: String,
        ): Finding {
            val fp = ViolationFingerprint.of(
                policyId = policyId,
                ruleId = ruleId,
                location = locationPath,
                resourceFingerprint = resourceFingerprint,
            )
            return Finding(
                policyId = policyId,
                ruleId = ruleId,
                resourceId = resourceId,
                severity = "error",
                state = state,
                location = Location(path),
                remediation = if (message.isBlank()) "fix resource to satisfy rule $ruleId" else message,
                fingerprint = fp.value,
            )
        }

        fun refusal(resourceId: String, reason: String): Finding = Finding(
            policyId = "-",
            ruleId = "-",
            resourceId = resourceId,
            severity = "error",
            state = FindingState.REFUSAL,
            location = Location(resourceId),
            remediation = reason,
            fingerprint = "-",
        )
    }
}

/**
 * Dedup identity: one finding per policyId|ruleId|resourceId|occurrence
 * (REQ-M9-02c + B3 "dedup sin pérdida"). Distinct violation locations inside
 * one resource stay separate; true duplicates of the same occurrence collapse.
 */
fun List<Finding>.dedup(): List<Finding> =
    distinctBy { "${it.policyId}|${it.ruleId}|${it.resourceId}|${it.fingerprint}|${it.state}" }

/**
 * Emitters for the three output formats (REQ-M9-03). Hand-rolled JSON in the
 * project style: no serialization dependency in the CLI bucket.
 */
object FindingsEmitter {

    private fun String.esc(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    fun text(findings: List<Finding>): String = buildString {
        if (findings.isEmpty()) appendLine("no findings")
        findings.forEach {
            appendLine(
                "[${it.state}] ${it.ruleId} on ${it.resourceId}" +
                    (it.location?.let { l -> " at ${l.render()}" } ?: "") +
                    " — ${it.remediation}",
            )
        }
    }

    fun json(findings: List<Finding>): String {
        val items = findings.joinToString(",\n") { f ->
            val loc = f.location?.let { l ->
                ", \"location\": {\"path\": \"${l.path.esc()}\"" +
                    (l.line?.let { ", \"line\": $it" } ?: "") +
                    (l.column?.let { ", \"column\": $it" } ?: "") +
                    "}"
            } ?: ""
            "  {\"policyId\": \"${f.policyId.esc()}\", \"ruleId\": \"${f.ruleId.esc()}\", " +
                "\"resourceId\": \"${f.resourceId.esc()}\", \"severity\": \"${f.severity}\", " +
                "\"state\": \"${f.state.name.lowercase()}\", \"remediation\": \"${f.remediation.esc()}\", " +
                "\"fingerprint\": \"${f.fingerprint.esc()}\"$loc}"
        }
        return "{\n  \"summary\": {\"total\": ${findings.size}},\n  \"findings\": [\n$items\n  ]\n}"
    }

    fun jsonl(findings: List<Finding>): String =
        findings.joinToString("\n") { f ->
            val loc = f.location?.let { l ->
                ",\"location\":{\"path\":\"${l.path.esc()}\"" +
                    (l.line?.let { ",\"line\":$it" } ?: "") +
                    (l.column?.let { ",\"column\":$it" } ?: "") +
                    "}"
            } ?: ""
            "{\"policyId\":\"${f.policyId.esc()}\",\"ruleId\":\"${f.ruleId.esc()}\"," +
                "\"resourceId\":\"${f.resourceId.esc()}\",\"severity\":\"${f.severity}\"," +
                "\"state\":\"${f.state.name.lowercase()}\",\"remediation\":\"${f.remediation.esc()}\"," +
                "\"fingerprint\":\"${f.fingerprint.esc()}\"$loc}"
        }
    }
