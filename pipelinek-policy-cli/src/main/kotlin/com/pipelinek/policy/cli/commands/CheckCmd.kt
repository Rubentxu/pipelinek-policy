package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.FindingsEmitter
import com.pipelinek.policy.cli.Finding
import com.pipelinek.policy.cli.dedup
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.policy.PolicyViolation
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import java.io.File

/**
 * M9 · `check --policy <bundle> <resources...> [--format text|json|jsonl]`
 * (REQ-M9-02). Decoder is chosen by file EXTENSION only — no content
 * sniffing. Unknown extension or refused decode is a REFUSAL finding and
 * dominates the exit code (error > violation > ok).
 */
object CheckCmd {

    private val decodersByExt: Map<String, ResourceDecoder> = mapOf(
        "json" to JsonResourceDecoder(),
        "yaml" to YamlResourceDecoder(),
        "yml" to YamlResourceDecoder(),
        "csv" to CsvResourceDecoder(),
    )

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val policyPath = args.flag("policy")
            ?: run {
                out("check: missing --policy <bundle>")
                return ExitCodes.USAGE
            }
        val format = args.flag("format") ?: "text"
        if (format !in setOf("text", "json", "jsonl")) {
            out("check: unknown --format $format (text|json|jsonl)")
            return ExitCodes.USAGE
        }
        val resources = args.positionals
        if (resources.isEmpty()) {
            out("check: no resources given")
            return ExitCodes.USAGE
        }

        val bundleFile = File(policyPath)
        if (!bundleFile.isFile) {
            out(FindingsEmitter.text(listOf(Finding.refusal(policyPath, "bundle not found: $policyPath"))))
            return ExitCodes.ADMISSION_ERROR
        }
        val verified = try {
            BundleVerifier.verifyPacked(bundleFile.readBytes())
        } catch (e: Exception) {
            out(FindingsEmitter.text(listOf(Finding.refusal(policyPath, "bundle refused: ${e.message}"))))
            return ExitCodes.ADMISSION_ERROR
        }

        val findings = mutableListOf<Finding>()
        for (res in resources) {
            findings += checkResource(verified, res)
        }
        val deduped = findings.dedup()
        emit(deduped, format, out)

        val hasRefusal = deduped.any { it.state == com.pipelinek.policy.cli.FindingState.REFUSAL }
        val hasEvaluationError = deduped.any { it.state == com.pipelinek.policy.cli.FindingState.ERROR }
        val hasViolation = deduped.any { it.state == com.pipelinek.policy.cli.FindingState.VIOLATION }
        return when {
            hasRefusal -> ExitCodes.ADMISSION_ERROR
            hasEvaluationError -> ExitCodes.EVALUATION_ERROR
            hasViolation -> ExitCodes.VIOLATIONS
            else -> ExitCodes.OK
        }
    }

    private fun checkResource(
        verified: com.pipelinek.policy.bundle.VerifiedBundle,
        resourcePath: String,
    ): List<Finding> {
        val file = File(resourcePath)
        if (!file.isFile) {
            return listOf(Finding.refusal(resourcePath, "resource not found: $resourcePath"))
        }
        val ext = file.extension.lowercase()
        val decoder = decodersByExt[ext]
            ?: return listOf(
                Finding.refusal(resourcePath, "no decoder for extension .$ext (by-extension only, no sniffing)"),
            )
        return when (val decoded = decoder.decode(file.readBytes(), DecodeOptions())) {
            is DecodeResult.Refused ->
                listOf(Finding.refusal(resourcePath, "decode refused: ${decoded.refusal.code}"))
            is DecodeResult.Ok -> decoded.documents.flatMap { doc ->
                val runtime = IrRuntimeAdapter.evaluate(verified, doc.root)
                runtime.report.results.entries.flatMap { (ruleId, evaluation) ->
                    when (evaluation) {
                        is RuleEvaluation.Error -> listOf(
                            findingFor(
                                ruleId = ruleId.value,
                                policyId = runtime.report.policySetId,
                                resourceId = doc.id.value,
                                path = resourcePath,
                                resourceFingerprint = runtime.report.resourceFingerprint,
                                violation = evaluation.primary,
                                isEvaluationError = true,
                                document = doc,
                                bundleDigest = verified.bundle.manifest.artifactDigest,
                            ),
                        )
                        is RuleEvaluation.Violated -> evaluation.violations.map { violation ->
                            findingFor(
                                ruleId = ruleId.value,
                                policyId = runtime.report.policySetId,
                                resourceId = doc.id.value,
                                path = resourcePath,
                                resourceFingerprint = runtime.report.resourceFingerprint,
                                violation = violation,
                                isEvaluationError = false,
                                document = doc,
                                bundleDigest = verified.bundle.manifest.artifactDigest,
                            )
                        }
                        RuleEvaluation.Passed, RuleEvaluation.NotApplicable -> emptyList()
                    }
                }
            }
        }
    }

    private fun findingFor(
        ruleId: String,
        policyId: String,
        resourceId: String,
        path: String,
        resourceFingerprint: String,
        violation: PolicyViolation,
        isEvaluationError: Boolean,
        document: com.pipelinek.policy.decoder.ResourceDocument?,
        bundleDigest: String,
    ): Finding {
        // B3: resolve the violation's logical path to the document's physical
        // anchor so findings carry line/column, not just the logical path.
        val physical = document?.let {
            com.pipelinek.policy.cli.PhysicalLocator.anchorFor(it, violation.location)
        }
        val line: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.TextSpan)?.startLine
        val column: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.TextSpan)?.startColumn
        val row: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.Cell)?.row
        val col: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.Cell)?.column
        return if (isEvaluationError) {
            Finding.error(
                policyId = policyId,
                ruleId = ruleId,
                resourceId = resourceId,
                path = path,
                message = violation.message,
                resourceFingerprint = resourceFingerprint,
                locationPath = violation.location.toString(),
                bundleDigest = bundleDigest,
                sourceAnchor = physical,
                actual = violation.actual,
                expected = violation.expected,
                line = line ?: row,
                column = column ?: col,
            )
        } else {
            Finding.violation(
                policyId = policyId,
                ruleId = ruleId,
                resourceId = resourceId,
                path = path,
                message = violation.message,
                resourceFingerprint = resourceFingerprint,
                locationPath = violation.location.toString(),
                bundleDigest = bundleDigest,
                sourceAnchor = physical,
                actual = violation.actual,
                expected = violation.expected,
                line = line ?: row,
                column = column ?: col,
            )
        }
    }

    private fun emit(findings: List<Finding>, format: String, out: (String) -> Unit) {
        when (format) {
            "json" -> out(FindingsEmitter.json(findings))
            "jsonl" -> out(FindingsEmitter.jsonl(findings))
            else -> out(FindingsEmitter.text(findings))
        }
    }
}
