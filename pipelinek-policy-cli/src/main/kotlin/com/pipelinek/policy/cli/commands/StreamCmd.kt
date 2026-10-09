package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
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
 *   0 OK            — stream evaluated, no violations
 *   1 VIOLATIONS    — at least one LOCAL rule violated
 *   2 USAGE         — usage error, plan refusal (GLOBAL rejected), decode
 *                     refusal, or budget exceeded
 *   3 INTERNAL      — unexpected crash
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
        val rowBudget = args.flag("row-budget")?.toLongOrNull()?.takeIf { it > 0 }
            ?: DatasetPlanner.DEFAULT_ROW_BUDGET
        val jsonOut = args.flag("format-out") == "json"

        val bundleFile = File(policyPath)
        if (!bundleFile.isFile) {
            out(msg(jsonOut, "refusal", "bundle not found: $policyPath"))
            return ExitCodes.USAGE
        }
        val verified = try {
            BundleVerifier.verifyPacked(bundleFile.readBytes())
        } catch (e: Exception) {
            out(msg(jsonOut, "refusal", "bundle refused: ${e.message}"))
            return ExitCodes.USAGE
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
            return ExitCodes.USAGE
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
                    return ExitCodes.USAGE
                }
                val rows = rowSupplierFor(file)
                    ?: run {
                        out(msg(jsonOut, "refusal", "no streaming source for .${
                            file.extension.lowercase()
                        } (csv|jsonl)"))
                        return ExitCodes.USAGE
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
                            return ExitCodes.USAGE
                        }
                        is StreamingEvaluator.RuleOutcome.Aggregate ->
                            sb.append("aggregate $ruleId = ${outcome.value}\n")
                        else -> Unit
                    }
                }
            }
        } catch (budget: BudgetExceededException) {
            out(msg(jsonOut, "budget-exceeded", budget.message ?: "budget exceeded"))
            return ExitCodes.USAGE
        } catch (mismatch: SumTypeMismatchException) {
            out(msg(jsonOut, "type-mismatch", mismatch.message ?: "type mismatch"))
            return ExitCodes.USAGE
        } catch (csv: CsvRowRefusal) {
            out(msg(jsonOut, "decode-refusal", "csv refused: ${csv.anchor}"))
            return ExitCodes.USAGE
        } catch (jsonl: JsonlRefusal) {
            out(msg(jsonOut, "decode-refusal", "jsonl refused: ${jsonl.anchor}"))
            return ExitCodes.USAGE
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

    /** Streaming sources by extension. Unknown → null (refusal). */
    private fun rowSupplierFor(file: File): StreamingEvaluator.RowSupplier? = when (
        file.extension.lowercase()
    ) {
        "csv" -> {
            val source = CsvRowSource(file.readBytes())
            StreamingEvaluator.RowSupplier { source.next() }
        }
        "jsonl", "ndjson" -> {
            val source = JsonlSource(file.readBytes())
            StreamingEvaluator.RowSupplier { source.next() }
        }
        else -> null
    }

    private fun msg(json: Boolean, kind: String, message: String): String =
        if (json) "{\"kind\":\"$kind\",\"message\":\"${message.replace("\"", "\\\"")}\"}"
        else "stream: $kind: $message"
}
