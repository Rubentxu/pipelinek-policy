package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.cli.BoundedRead
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.decoders.csv.CsvRowRefusal
import com.pipelinek.policy.decoders.csv.CsvRowSource
import com.pipelinek.policy.decoders.json.JsonlRefusal
import com.pipelinek.policy.decoders.json.JsonlSource
import com.pipelinek.policy.kernel.dataset.Accumulator
import com.pipelinek.policy.kernel.dataset.BudgetExceededException
import com.pipelinek.policy.kernel.dataset.DatasetPlanner
import com.pipelinek.policy.kernel.dataset.DatasetSpec
import com.pipelinek.policy.kernel.dataset.PlanRefusal
import com.pipelinek.policy.kernel.dataset.StreamingEvaluator
import com.pipelinek.policy.kernel.dataset.SumTypeMismatchException
import java.io.File

/**
 * M8 WU-6 (REQ-M8-07) · `stream --policy <bundle> <dataset...>`
 * [--format csv|jsonl] [--row-budget N] [--format-out text|json].
 *
 * Decoder by extension (csv → CsvRowSource, jsonl → JsonlSource); no
 * sniffing. Exit-code matrix (REQ-M8-07 scenarios 07a..07d):
 *   0 OK                — stream evaluated, no violations
 *   1 USAGE             — invocation error (missing/invalid flags)
 *   2 VIOLATIONS        — at least one LOCAL rule violated
 *   3 EVALUATION_ERROR  — plan refusal, budget exceeded, type mismatch
 *   4 ADMISSION_ERROR   — bundle/dataset admission or decode refusal
 */
object StreamCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val policyPath = args.flag("policy")
            ?: run {
                out("stream: missing --policy <bundle>")
                return ExitCodes.USAGE
            }
        val resources = args.positionals
        if (resources.isEmpty()) {
            out("stream: no dataset files given")
            return ExitCodes.USAGE
        }
        val rowBudget = args.flag("row-budget")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 } ?: run {
                out("stream: invalid --row-budget $raw (must be a positive integer)")
                return ExitCodes.USAGE
            }
        } ?: DatasetPlanner.DEFAULT_ROW_BUDGET
        val formatOut = args.flag("format-out") ?: "text"
        if (formatOut !in setOf("text", "json")) {
            out("stream: unknown --format-out $formatOut (text|json)")
            return ExitCodes.USAGE
        }
        val jsonOut = formatOut == "json"

        val bundleFile = File(policyPath)
        if (!bundleFile.isFile) {
            out(msg(jsonOut, "refusal", "bundle not found: $policyPath"))
            return ExitCodes.ADMISSION_ERROR
        }
        val verified = try {
            val _bounded = BoundedRead.readOrReport(bundleFile, "stream", out)
            ?: return ExitCodes.ADMISSION_ERROR
            BundleVerifier.verifyPacked(_bounded)
        } catch (e: Exception) {
            out(msg(jsonOut, "refusal", "bundle refused: ${e.message}"))
            return ExitCodes.ADMISSION_ERROR
        }

        val policySet = verified.bundle.document.policySet
        val datasets = resources.associate {
            it to DatasetSpec(id = File(it).nameWithoutExtension, format = File(it).extension)
        }

        // Plan (may refuse: GLOBAL rejected, law 12).
        val plan = DatasetPlanner.plan(policySet, datasets, rowBudget = rowBudget)
        if (plan is PlanRefusal) {
            out(
                msg(
                    jsonOut,
                    "plan-refusal",
                    "plan refused (${plan.offendingRules.joinToString(",")}): ${plan.reason}",
                ),
            )
            return ExitCodes.EVALUATION_ERROR
        }
        plan as com.pipelinek.policy.kernel.dataset.DatasetPlan

        var violations = 0L
        var lastViolationRow = -1L
        val sb = StringBuilder()

        try {
            for (resource in resources) {
                val file = File(resource)
                if (!file.isFile) {
                    out(msg(jsonOut, "refusal", "dataset not found: $resource"))
                    return ExitCodes.ADMISSION_ERROR
                }
                val rows = rowSupplierFor(file, out)
                    ?: run {
                        out(msg(jsonOut, "refusal", "no streaming source for .${
                            file.extension.lowercase()
                        } (csv|jsonl)"))
                        return ExitCodes.ADMISSION_ERROR
                    }
                val report = StreamingEvaluator.evaluate(policySet, plan, rows)
                report.results.forEach { (ruleId, outcome) ->
                    when (outcome) {
                        is StreamingEvaluator.RuleOutcome.Violated -> {
                            violations += outcome.rowCount
                            lastViolationRow = outcome.lastRowNumber
                        }
                        is StreamingEvaluator.RuleOutcome.Error -> {
                            out(msg(jsonOut, "refusal", "rule $ruleId: ${outcome.message}"))
                            return ExitCodes.EVALUATION_ERROR
                        }
                        is StreamingEvaluator.RuleOutcome.Aggregate ->
                            sb.append("aggregate $ruleId = ${outcome.value}\n")
                        else -> Unit
                    }
                }
            }
        } catch (budget: BudgetExceededException) {
            out(msg(jsonOut, "budget-exceeded", budget.message ?: "budget exceeded"))
            return ExitCodes.EVALUATION_ERROR
        } catch (mismatch: SumTypeMismatchException) {
            out(msg(jsonOut, "type-mismatch", mismatch.message ?: "type mismatch"))
            return ExitCodes.EVALUATION_ERROR
        } catch (csv: CsvRowRefusal) {
            out(msg(jsonOut, "decode-refusal", "csv refused: ${csv.anchor}"))
            return ExitCodes.ADMISSION_ERROR
        } catch (jsonl: JsonlRefusal) {
            out(msg(jsonOut, "decode-refusal", "jsonl refused: ${jsonl.anchor}"))
            return ExitCodes.ADMISSION_ERROR
        }

        if (!jsonOut && sb.isNotEmpty()) out(sb.toString().trimEnd())
        out(
            if (jsonOut) {
                "{\"status\":\"${if (violations == 0L) "ok" else "violations"}\"," +
                    "\"violations\":$violations,\"lastViolationRow\":$lastViolationRow}"
            } else {
                "stream: ${if (violations == 0L) "ok" else "violations=$violations " +
                    "(last row $lastViolationRow)"}"
            },
        )
        return if (violations > 0) ExitCodes.VIOLATIONS else ExitCodes.OK
    }

    /**
     * Streaming sources by extension. Unknown → null (refusal).
     *
     * The file is read under the resource budget before the cursor is built,
     * so an oversized dataset is refused instead of being loaded whole. [out]
     * is threaded in because a size refusal must reach the operator, not just
     * collapse into a null the caller would read as "unsupported extension".
     */
    private fun rowSupplierFor(file: File, out: (String) -> Unit): StreamingEvaluator.RowSupplier? = when (
        file.extension.lowercase()
    ) {
        "csv" -> {
            val bytes = BoundedRead.readOrReport(
                file,
                "stream source",
                out,
                BoundedRead.DEFAULT_RESOURCE_BUDGET,
            ) ?: return null
            val source = CsvRowSource(bytes)
            StreamingEvaluator.RowSupplier { source.next() }
        }
        "jsonl", "ndjson" -> {
            val bytes = BoundedRead.readOrReport(
                file,
                "stream source",
                out,
                BoundedRead.DEFAULT_RESOURCE_BUDGET,
            ) ?: return null
            val source = JsonlSource(bytes)
            StreamingEvaluator.RowSupplier { source.next() }
        }
        else -> null
    }

    private fun msg(json: Boolean, kind: String, message: String): String =
        if (json) "{\"kind\":\"$kind\",\"message\":\"${message.replace("\"", "\\\"")}\"}"
        else "stream: $kind: $message"
}
