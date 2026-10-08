package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.ir.CanonicalPolicyJson
import java.io.File

/**
 * M9 · `inspect --policy <bundle>` (REQ-M9-07): dumps the canonical IR
 * JSON of a verified bundle. Output re-parses via CanonicalPolicyJson
 * (07b).
 */
object InspectCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val path = args.flag("policy") ?: run {
            out("inspect: missing --policy <bundle>")
            return ExitCodes.USAGE
        }
        val file = File(path)
        if (!file.isFile) {
            out("inspect: bundle not found: $path")
            return ExitCodes.USAGE
        }
        val verified = try {
            BundleVerifier.verifyPacked(file.readBytes())
        } catch (e: Exception) {
            out("inspect: bundle refused: ${e.message}")
            return ExitCodes.USAGE
        }
        out(CanonicalPolicyJson.encode(verified.bundle.document).toString(Charsets.UTF_8))
        return ExitCodes.OK
    }
}
