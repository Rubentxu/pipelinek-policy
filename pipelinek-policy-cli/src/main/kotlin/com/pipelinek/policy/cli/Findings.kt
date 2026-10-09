package com.pipelinek.policy.cli

import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.policy.ViolationFingerprint

/**
 * M9 compatibility fields plus the B3 structured CLI finding contract. The
 * agent-facing schema is emitted from this typed model, never human text.
 */
data class Location(
    val path: String,
    val line: Long? = null,
    val column: Long? = null,
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
    val bundleDigest: String? = null,
    val subjectRef: String = resourceId,
    val sourceAnchor: SourceAnchor? = null,
    val path: String? = null,
    val actual: String? = null,
    val expected: String? = null,
    val message: String = remediation,
) {
    val violationId: String? get() = fingerprint.takeUnless { it == "-" }

    companion object {
        fun violation(
            policyId: String,
            ruleId: String,
            resourceId: String,
            path: String,
            message: String,
            resourceFingerprint: String,
            locationPath: String,
            bundleDigest: String,
            sourceAnchor: SourceAnchor? = null,
            actual: String? = null,
            expected: String? = null,
            line: Long? = null,
            column: Long? = null,
        ): Finding = actionableFailure(
            state = FindingState.VIOLATION,
            policyId = policyId,
            ruleId = ruleId,
            resourceId = resourceId,
            path = path,
            message = message,
            resourceFingerprint = resourceFingerprint,
            locationPath = locationPath,
            bundleDigest = bundleDigest,
            sourceAnchor = sourceAnchor,
            actual = actual,
            expected = expected,
            line = line,
            column = column,
        )

        fun error(
            policyId: String,
            ruleId: String,
            resourceId: String,
            path: String,
            message: String,
            resourceFingerprint: String,
            locationPath: String,
            bundleDigest: String,
            sourceAnchor: SourceAnchor? = null,
            actual: String? = null,
            expected: String? = null,
            line: Long? = null,
            column: Long? = null,
        ): Finding = actionableFailure(
            state = FindingState.ERROR,
            policyId = policyId,
            ruleId = ruleId,
            resourceId = resourceId,
            path = path,
            message = message,
            resourceFingerprint = resourceFingerprint,
            locationPath = locationPath,
            bundleDigest = bundleDigest,
            sourceAnchor = sourceAnchor,
            actual = actual,
            expected = expected,
            line = line,
            column = column,
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
            bundleDigest: String,
            sourceAnchor: SourceAnchor?,
            actual: String?,
            expected: String?,
            line: Long?,
            column: Long?,
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
                location = Location(path, line, column),
                remediation = if (message.isBlank()) "fix resource to satisfy rule $ruleId" else message,
                fingerprint = fp.value,
                bundleDigest = bundleDigest,
                subjectRef = resourceId,
                sourceAnchor = sourceAnchor,
                path = locationPath,
                actual = actual,
                expected = expected,
                message = message,
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
            message = reason,
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

    private fun String.esc(): String = buildString(length) {
        for (char in this@esc) {
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
                    append(char)
                }
            }
        }
    }

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
        val items = findings.joinToString(",\n") { "  ${it.jsonObject(pretty = true)}" }
        return "{\n  \"summary\": {\"total\": ${findings.size}},\n  \"findings\": [\n$items\n  ]\n}"
    }

    fun jsonl(findings: List<Finding>): String =
        findings.joinToString("\n") { it.jsonObject(pretty = false) }

    private fun Finding.jsonObject(pretty: Boolean): String {
        val fields = mutableListOf(
            "violationId" to nullableJsonString(violationId),
            "policyId" to jsonString(policyId),
            "ruleId" to jsonString(ruleId),
            "bundleDigest" to nullableJsonString(bundleDigest),
            "subjectRef" to jsonString(subjectRef),
            "sourceAnchor" to (sourceAnchor?.toJson() ?: "null"),
            "path" to nullableJsonString(path),
            "actual" to nullableJsonString(actual),
            "expected" to nullableJsonString(expected),
            "message" to jsonString(message),
            "remediation" to jsonString(remediation),
            "severity" to jsonString(severity),
            "state" to jsonString(state.name.lowercase()),
            "resourceId" to jsonString(resourceId),
            "fingerprint" to jsonString(fingerprint),
        )
        location?.let { fields.add("location" to it.toJson()) }
        val fieldSeparator = if (pretty) ", " else ","
        val keyValueSeparator = if (pretty) ": " else ":"
        return fields.joinToString(fieldSeparator, prefix = "{", postfix = "}") { (key, value) ->
            "${jsonString(key)}$keyValueSeparator$value"
        }
    }

    private fun Location.toJson(): String = buildString {
        append("{\"path\":").append(jsonString(path))
        line?.let { append(",\"line\":").append(it) }
        column?.let { append(",\"column\":").append(it) }
        append('}')
    }

    private fun SourceAnchor.toJson(): String = when (this) {
        is SourceAnchor.TextSpan ->
            "{\"kind\":\"textSpan\",\"startLine\":$startLine,\"startColumn\":$startColumn," +
                "\"endLine\":$endLine,\"endColumn\":$endColumn}"
        is SourceAnchor.Cell -> "{\"kind\":\"cell\",\"row\":$row,\"column\":$column}"
        is SourceAnchor.Element -> "{\"kind\":\"element\",\"elementId\":${jsonString(elementId)}}"
        is SourceAnchor.Logical -> "{\"kind\":\"logical\",\"path\":${jsonString(path)}}"
    }

    private fun jsonString(value: String): String = "\"${value.esc()}\""

    private fun nullableJsonString(value: String?): String = value?.let(::jsonString) ?: "null"
}
