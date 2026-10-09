package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.kernel.expression.Expression
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
            return ExitCodes.USAGE
        }
        val verified = try {
            BundleVerifier.verifyPacked(file.readBytes())
        } catch (e: Exception) {
            out("explain: bundle refused: ${e.message}")
            return ExitCodes.USAGE
        }
        val policy = verified.bundle.document.policySet
        val rule = policy.policies.flatMap { p -> p.rules.map { p to it } }
            .firstOrNull { (_, r) -> r.id == ruleId }
        if (rule == null) {
            out("explain: unknown rule: $ruleId (known: ${policy.policies.flatMap { it.rules.map { r -> r.id } }.joinToString(", ")})")
            return ExitCodes.USAGE
        }
        val (owningPolicy, r) = rule

        val state: String
        val violations: List<String>
        val resourcePath = args.flag("resource")
        if (resourcePath == null) {
            state = "unevaluated (no --resource)"
            violations = emptyList()
        } else {
            val doc = decodeOne(resourcePath, out) ?: return ExitCodes.USAGE
            val report = IrRuntimeAdapter.evaluate(verified, doc.root).report
            val evaluation = report.results.entries.firstOrNull { it.key.value == ruleId }
            state = evaluation?.value?.let { it::class.simpleName?.uppercase() } ?: "UNKNOWN"
            violations = evaluation?.value?.violations?.map { it.location.toString() } ?: emptyList()
        }

        out(
            buildString {
                appendLine("{")
                appendLine("  \"ruleId\": \"$ruleId\",")
                appendLine("  \"policyId\": \"${policy.id}\",")
                appendLine("  \"owningPolicy\": \"${owningPolicy.id}\",")
                appendLine("  \"state\": \"${state.lowercase()}\",")
                appendLine("  \"expression\": ${renderExpression(r.expression)},")
                r.appliesWhen?.let { aw ->
                    appendLine("  \"appliesWhen\": ${renderExpression(aw)},")
                }
                appendLine("  \"violations\": [${violations.joinToString(",") { "\"$it\"" }}]")
                append("}")
            },
        )
        return ExitCodes.OK
    }

    /** Structural rendering of the expression tree (hand JSON, no deps). */
    private fun renderExpression(e: Expression): String = when (e) {
        is Expression.Literal ->
            "{\"kind\": \"literal\", \"value\": \"${e.value}\"}"
        is Expression.FieldRef ->
            "{\"kind\": \"fieldRef\", \"path\": \"${e.path}\"}"
        is Expression.Comparison ->
            "{\"kind\": \"comparison\", \"op\": \"${e.op}\", " +
                "\"left\": ${renderExpression(e.left)}, \"right\": ${renderExpression(e.right)}}"
        is Expression.Reference ->
            "{\"kind\": \"reference\", \"name\": \"${e.name}\"}"
        is Expression.DatasetRef ->
            "{\"kind\": \"datasetRef\", \"name\": \"${e.name}\"}"
        is Expression.CollectionPredicate ->
            "{\"kind\": \"collectionPredicate\", \"op\": \"${e.op}\", \"predicate\": \"${e.predicate}\", " +
                "\"source\": ${renderExpression(e.source)}}"
    }

    private fun decodeOne(resource: String, out: (String) -> Unit) =
        when (val d = decoderFor(File(resource).extension.lowercase())) {
            null -> {
                out("explain: no decoder for $resource (by-extension only)")
                null
            }
            else -> when (val decoded = d.decode(File(resource).readBytes(), DecodeOptions())) {
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
