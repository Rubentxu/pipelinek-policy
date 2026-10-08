package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import java.io.File

/**
 * M9 · `test --policy <bundle> --fixtures <dir>` (REQ-M9-08).
 *
 * Fixture dir layout: `<name>.allow.<ext>` (must have NO violations) and
 * `<name>.deny.<ext>` (must have at least one violation). A fixture whose
 * expectation mismatches produces a per-fixture result line and exit 1.
 */
object TestCmd {

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val policyPath = args.flag("policy") ?: run {
            out("test: missing --policy <bundle>")
            return ExitCodes.USAGE
        }
        val fixturesDir = args.flag("fixtures") ?: run {
            out("test: missing --fixtures <dir>")
            return ExitCodes.USAGE
        }
        val dir = File(fixturesDir)
        if (!dir.isDirectory) {
            out("test: fixtures dir not found: $fixturesDir")
            return ExitCodes.USAGE
        }
        val bundleFile = File(policyPath)
        if (!bundleFile.isFile) {
            out("test: bundle not found: $policyPath")
            return ExitCodes.USAGE
        }
        val verified = try {
            BundleVerifier.verifyPacked(bundleFile.readBytes())
        } catch (e: Exception) {
            out("test: bundle refused: ${e.message}")
            return ExitCodes.USAGE
        }

        val fixtures = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (fixtures.isEmpty()) {
            out("test: no fixtures in $fixturesDir")
            return ExitCodes.USAGE
        }

        var mismatches = 0
        for (fixture in fixtures) {
            val expectation = when {
                ".allow." in fixture.name -> "allow"
                ".deny." in fixture.name -> "deny"
                else -> continue // not a fixture file
            }
            val result = evaluate(verified, fixture)
            if (result == null) {
                out("{\"fixture\": \"${fixture.name}\", \"status\": \"refused\"}")
                mismatches++
                continue
            }
            val violations = result
            val ok = when (expectation) {
                "allow" -> violations == 0
                else -> violations > 0
            }
            if (!ok) mismatches++
            out(
                "{\"fixture\": \"${fixture.name}\", \"expectation\": \"$expectation\", " +
                    "\"violations\": $violations, \"status\": \"${if (ok) "pass" else "FAIL"}\"}",
            )
        }
        return if (mismatches > 0) ExitCodes.VIOLATIONS else ExitCodes.OK
    }

    private fun evaluate(
        verified: com.pipelinek.policy.bundle.VerifiedBundle,
        fixture: File,
    ): Int? {
        val decoder = when (fixture.extension.lowercase()) {
            "json" -> com.pipelinek.policy.decoders.json.JsonResourceDecoder()
            "yaml", "yml" -> com.pipelinek.policy.decoders.yaml.YamlResourceDecoder()
            "csv" -> com.pipelinek.policy.decoders.csv.CsvResourceDecoder()
            else -> return null
        }
        return when (val decoded = decoder.decode(fixture.readBytes(), DecodeOptions())) {
            is DecodeResult.Refused -> null
            is DecodeResult.Ok -> decoded.documents.count { doc ->
                IrRuntimeAdapter.evaluate(verified, doc.root).report.results.values
                    .any { it.violations.isNotEmpty() }
            }
        }
    }
}
