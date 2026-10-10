package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.cli.JsonString
import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.BoundedRead
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDocument
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import java.io.File

/**
 * M9 · `explain --policy <bundle> --rule <ruleId> [--resource <res>]`
 * (REQ-M9-07). v1 scope (design §4): ruleId + rule state + the rule's
 * expression tree rendered from the IR, plus each violation's location
 * when a resource is given. The Evaluator does not expose per-node traces
 * yet, so the "culprit subtree" v1 is the full rule expression with the
 * violated location(s) — the ruleId and tree are still identifiable
 * (07a/07c).
 */
object ExplainCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val policyPath = args.flag("policy") ?: run {
            out("explain: missing --policy <bundle>")
            return ExitCodes.USAGE
        }
        val ruleId = args.flag("rule") ?: run {
            out("explain: missing --rule <ruleId>")
            return ExitCodes.USAGE
        }
        val file = File(policyPath)
        if (!file.isFile) {
            out("explain: bundle not found: $policyPath")
            return ExitCodes.ADMISSION_ERROR
        }
        val verified = try {
            val _bounded = BoundedRead.readOrReport(file, "explain", out)
            ?: return ExitCodes.ADMISSION_ERROR
            BundleVerifier.verifyPacked(_bounded)
        } catch (e: Exception) {
            out("explain: bundle refused: ${e.message}")
            return ExitCodes.ADMISSION_ERROR
        }
        val policy = verified.bundle.document.policySet
        val matches = policy.policies.flatMap { p -> p.rules.map { p to it } }
            .filter { (owner, r) ->
                r.id == ruleId || RuleKey.of(policy.id, owner.id, r.id).value == ruleId
            }
        if (matches.isEmpty()) {
            val known = policy.policies.flatMap { p -> p.rules.map { r -> "${p.id}/${r.id}" } }
            out("explain: unknown rule: $ruleId (known: ${known.joinToString(", ")})")
            return ExitCodes.USAGE
        }
        if (matches.size > 1) {
            val qualified = matches.map { (owner, r) -> RuleKey.of(policy.id, owner.id, r.id).value }
            out("explain: ambiguous rule: $ruleId (use one of: ${qualified.joinToString(", ")})")
            return ExitCodes.USAGE
        }
        val (owningPolicy, r) = matches.single()

        val state: String
        val violations: List<String>
        var exitCode = ExitCodes.OK
        val resourcePath = args.flag("resource")
        if (resourcePath == null) {
            state = "unevaluated (no --resource)"
            violations = emptyList()
        } else {
            val doc = decodeOne(resourcePath, out) ?: return ExitCodes.ADMISSION_ERROR
            val report = IrRuntimeAdapter.evaluate(verified, doc.root).report
            val evaluation = report.results[RuleKey.of(policy.id, owningPolicy.id, r.id)]
            state = evaluation?.let { it::class.simpleName?.uppercase() } ?: "UNKNOWN"
            violations = evaluation?.violations?.map { it.location.toString() } ?: emptyList()
            when (evaluation) {
                is RuleEvaluation.Violated -> exitCode = ExitCodes.VIOLATIONS
                is RuleEvaluation.Error -> exitCode = ExitCodes.EVALUATION_ERROR
                else -> Unit
            }
        }

        out(
            buildString {
                appendLine("{")
                appendLine("  \"policySetId\": ${JsonString.str(policy.id)},")
                appendLine("  \"ruleId\": ${JsonString.str(r.id)},")
                appendLine("  \"policyId\": ${JsonString.str(owningPolicy.id)},")
                appendLine("  \"owningPolicy\": ${JsonString.str(owningPolicy.id)},")
                appendLine("  \"state\": ${JsonString.str(state.lowercase())},")
                appendLine("  \"expression\": ${renderExpression(r.expression)},")
                r.appliesWhen?.let { aw ->
                    appendLine("  \"appliesWhen\": ${renderExpression(aw)},")
                }
                appendLine("  \"violations\": [${violations.joinToString(",") { JsonString.str(it) }}]")
                append("}")
            },
        )
        return exitCode
    }

    /** Structural rendering of the expression tree (hand JSON, no deps). */
    private fun renderExpression(e: Expression): String = when (e) {
        is Expression.Literal ->
            "{\"kind\": \"literal\", \"value\": ${JsonString.str(e.value.toString())}}"
        is Expression.FieldRef ->
            "{\"kind\": \"fieldRef\", \"path\": ${JsonString.str(e.path.toString())}, " +
                "\"optional\": ${e.optional}}"
        is Expression.Comparison ->
            "{\"kind\": \"comparison\", \"op\": ${JsonString.str(e.op.name)}, " +
                "\"left\": ${renderExpression(e.left)}, \"right\": ${renderExpression(e.right)}}"
        is Expression.Reference ->
            "{\"kind\": \"reference\", \"name\": ${JsonString.str(e.name)}}"
        is Expression.DatasetRef ->
            "{\"kind\": \"datasetRef\", \"name\": ${JsonString.str(e.name)}}"
        is Expression.Not ->
            "{\"kind\": \"not\", \"body\": ${renderExpression(e.body)}}"
        is Expression.CollectionPredicate ->
            "{\"kind\": \"collectionPredicate\", \"op\": ${JsonString.str(e.op.name)}, \"predicate\": ${JsonString.str(e.predicate.toString())}, " +
                "\"source\": ${renderExpression(e.source)}}"
    }

    private fun decodeOne(resource: String, out: (String) -> Unit): ResourceDocument? {
        val d = decoderFor(File(resource).extension.lowercase())
            ?: run {
                out("explain: no decoder for $resource (by-extension only)")
                return null
            }
        val resourceBytes = BoundedRead.readOrReport(
            File(resource),
            "explain resource",
            out,
            BoundedRead.DEFAULT_RESOURCE_BUDGET,
        ) ?: return null
        return when (val decoded = d.decode(resourceBytes, DecodeOptions())) {
                is DecodeResult.Refused -> {
                    out("explain: decode refused: ${decoded.refusal.code}")
                    null
                }
                is DecodeResult.Ok -> decoded.documents.firstOrNull()
            }
        }

    private fun decoderFor(ext: String) = when (ext) {
        "json" -> com.pipelinek.policy.decoders.json.JsonResourceDecoder()
        "yaml", "yml" -> com.pipelinek.policy.decoders.yaml.YamlResourceDecoder()
        "csv" -> com.pipelinek.policy.decoders.csv.CsvResourceDecoder()
        else -> null
    }
}
