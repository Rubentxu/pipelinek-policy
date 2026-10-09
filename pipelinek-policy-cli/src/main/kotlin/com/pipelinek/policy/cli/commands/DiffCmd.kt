package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.kernel.policy.PolicyDiff
import com.pipelinek.policy.kernel.policy.PolicyDiffRefusal
import java.io.File

/**
 * M9 · `diff --a <bundle> --b <bundle> [--corpus res...]` (REQ-M9-08).
 * Delegates directly to PolicyDiff (M7) — the CLI never reimplements
 * diff semantics (falsified by 08d: CLI digest must equal API digest).
 */
object DiffCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val pathA = args.flag("a") ?: return usage(out)
        val pathB = args.flag("b") ?: return usage(out)

        val a = loadVerified(pathA, out) ?: return ExitCodes.ADMISSION_ERROR
        val b = loadVerified(pathB, out) ?: return ExitCodes.ADMISSION_ERROR

        val corpus = args.flag("corpus")?.let { listOf(it) } ?: args.positionals
        if (corpus.isEmpty()) {
            out("diff: needs a resource to evaluate both bundles against (--corpus <res> or positional)")
            return ExitCodes.USAGE
        }
        val resource = corpus.first()
        val doc = decodeOne(resource, out) ?: return ExitCodes.ADMISSION_ERROR

        val reportA = IrRuntimeAdapter.evaluate(a, doc.root).report
        val reportB = IrRuntimeAdapter.evaluate(b, doc.root).report
        val diff = try {
            PolicyDiff.of(reportA, reportB)
        } catch (e: PolicyDiffRefusal.CorpusMismatch) {
            out("diff: corpus mismatch: ${e.message}")
            return ExitCodes.EVALUATION_ERROR
        }
        out(diffJson(diff))
        return if (diff.entries.isEmpty()) ExitCodes.OK else ExitCodes.VIOLATIONS
    }

    private fun usage(out: (String) -> Unit): Int {
        out("diff: missing --a and/or --b bundle paths")
        return ExitCodes.USAGE
    }

    private fun loadVerified(path: String, out: (String) -> Unit) = try {
        BundleVerifier.verifyPacked(File(path).readBytes())
    } catch (e: Exception) {
        out("diff: bundle refused: $path (${e.message})")
        null
    }

    internal fun diffJson(diff: PolicyDiff): String {
        val entries = diff.entries.joinToString(",\n") { e ->
            "  {\"category\": \"${e.category.name}\", \"policySetId\": \"${e.policySetId}\", " +
                "\"policyId\": \"${e.policyId}\", " +
                "\"ruleId\": \"${e.ruleId}\", " +
                "\"fingerprint\": \"${e.fingerprint}\", \"privilegeExpansion\": ${e.privilegeExpansion}}"
        }
        return "{\n  \"summary\": {\"entries\": ${diff.entries.size}},\n" +
            "  \"digest\": \"${diff.diffDigest}\",\n  \"entries\": [\n$entries\n  ]\n}"
    }

    private fun decodeOne(resource: String, out: (String) -> Unit) =
        when (val d = decoderFor(File(resource).extension.lowercase())) {
            null -> {
                out("diff: no decoder for $resource (by-extension only)")
                null
            }
            else -> when (val decoded = d.decode(File(resource).readBytes(), DecodeOptions())) {
                is DecodeResult.Refused -> {
                    out("diff: decode refused: ${decoded.refusal.code}")
                    null
                }
                is DecodeResult.Ok -> decoded.documents.firstOrNull()
            }
        }

    private fun decoderFor(ext: String): ResourceDecoder? = when (ext) {
        "json" -> JsonResourceDecoder()
        "yaml", "yml" -> YamlResourceDecoder()
        "csv" -> CsvResourceDecoder()
        else -> null
    }
}
