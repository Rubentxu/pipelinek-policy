package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.BoundedRead
import com.pipelinek.policy.cli.ExitCodes
import java.io.File

/**
 * M9 · `shape --policy <bundle>` (REQ-M9-08): structural summary
 * (policies, rules, params per rule) WITHOUT evaluation. Counts derive
 * from the verified IR — never re-parsed by hand (08b).
 */
object ShapeCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val path = args.flag("policy") ?: run {
            out("shape: missing --policy <bundle>")
            return ExitCodes.USAGE
        }
        val file = File(path)
        if (!file.isFile) {
            out("shape: bundle not found: $path")
            return ExitCodes.ADMISSION_ERROR
        }
        val verified = try {
            val _bounded = BoundedRead.readOrReport(file, "shape", out)
            ?: return ExitCodes.ADMISSION_ERROR
            BundleVerifier.verifyPacked(_bounded)
        } catch (e: Exception) {
            out("shape: bundle refused: ${e.message}")
            return ExitCodes.ADMISSION_ERROR
        }
        val doc = verified.bundle.document
        val policies = doc.policySet.policies
        val rules = policies.flatMap { it.rules }
        val withParams = rules.count { it.params.isNotEmpty() }
        out(
            "{\n" +
                "  \"policySetId\": \"${doc.policySet.id}\",\n" +
                "  \"policies\": ${policies.size},\n" +
                "  \"rules\": ${rules.size},\n" +
                "  \"rulesWithParams\": $withParams,\n" +
                "  \"functions\": ${doc.functions.size},\n" +
                "  \"shapeConstraints\": ${doc.shapes.size}\n" +
                "}",
        )
        return ExitCodes.OK
    }
}
