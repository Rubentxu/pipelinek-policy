package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import java.io.File

/**
 * M9 · `bundle verify <bundle...>` (REQ-M9-08). Delegates to BundleVerifier
 * (M5): digest + capability checks. Any refusal ⇒ exit 2.
 */
object BundleCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        if (!args.has("verify")) {
            out("bundle: unknown operation (supported: verify)")
            return ExitCodes.USAGE
        }
        // `--verify <path>` (flag-value form) or bare `--verify` + positionals.
        val targets = args.positionals.ifEmpty { listOfNotNull(args.flag("verify")) }
        if (targets.isEmpty()) {
            out("bundle verify: no bundles given")
            return ExitCodes.USAGE
        }
        var failed = false
        for (path in targets) {
            val file = File(path)
            if (!file.isFile) {
                out("{\"path\": \"$path\", \"verified\": false, \"reason\": \"not found\"}")
                failed = true
                continue
            }
            try {
                val verified = BundleVerifier.verifyPacked(file.readBytes())
                out(
                    "{\"path\": \"$path\", \"verified\": true, " +
                        "\"semanticDigest\": \"${verified.semanticDigest}\", " +
                        "\"policySetId\": \"${verified.bundle.document.policySet.id}\"}",
                )
            } catch (e: Exception) {
                out("{\"path\": \"$path\", \"verified\": false, \"reason\": \"${e.message}\"}")
                failed = true
            }
        }
        return if (failed) ExitCodes.USAGE else ExitCodes.OK
    }
}
