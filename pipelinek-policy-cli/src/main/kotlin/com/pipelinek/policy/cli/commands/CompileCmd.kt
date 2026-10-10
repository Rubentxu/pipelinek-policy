package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.BoundedRead
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.ir.CanonicalPolicyJson
import java.io.File

/**
 * M9 · `compile --policy <ir.json> --out <bundle>` (REQ-M9-01a).
 *
 * Scope note (design §1 + M4 Option C): compiling `.kts` authoring sugar
 * requires the FIR path documented as FAIL in M4; the canonical IR JSON
 * is the executable authority (arch law 2), so compile v1 lowers
 * IR JSON -> verified pack deterministically. When authoring returns,
 * compile gains the .kts source branch without changing this contract.
 */
object CompileCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val src = args.flag("policy") ?: run {
            out("compile: missing --policy <ir.json>")
            return ExitCodes.USAGE
        }
        val dst = args.flag("out") ?: run {
            out("compile: missing --out <bundle>")
            return ExitCodes.USAGE
        }
        val file = File(src)
        if (!file.isFile) {
            out("compile: policy source not found: $src")
            return ExitCodes.COMPILER_ERROR
        }
        val document = try {
            BoundedRead.readOrReport(file, "compile source", out, BoundedRead.DEFAULT_RESOURCE_BUDGET)
                ?.let { CanonicalPolicyJson.decode(it) }
                ?: return ExitCodes.ADMISSION_ERROR
        } catch (e: Exception) {
            out("compile: canonical IR refused: ${e.message}")
            return ExitCodes.COMPILER_ERROR
        }
        val bytes = PolicyBundle(document).pack()
        File(dst).writeBytes(bytes)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        out("{\"out\": \"$dst\", \"bundleSha256\": \"$digest\", \"bytes\": ${bytes.size}}")
        return ExitCodes.OK
    }
}
