package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.BoundedRead
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import java.io.File

/**
 * M9 · `test --policy <bundle> --fixtures <dir>` (REQ-M9-08).
 *
 * Fixture dir layout: `<name>.allow.<ext>` (must have NO violations) and
 * `<name>.deny.<ext>` (must have at least one violation). A fixture whose
 * expectation mismatches produces a per-fixture result line and exit 2.
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
            return ExitCodes.ADMISSION_ERROR
        }
        val verified = try {
            val _bounded = BoundedRead.readOrReport(bundleFile, "test", out)
            ?: return ExitCodes.ADMISSION_ERROR
            BundleVerifier.verifyPacked(_bounded)
        } catch (e: Exception) {
            out("test: bundle refused: ${e.message}")
            return ExitCodes.ADMISSION_ERROR
        }

        val fixtures = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val recognized = fixtures.filter { ".allow." in it.name || ".deny." in it.name }
        if (recognized.isEmpty()) {
            out("test: no recognized allow/deny fixtures in $fixturesDir")
            return ExitCodes.USAGE
        }

        var exitCode = ExitCodes.OK
        for (fixture in recognized) {
            val expectation = if (".allow." in fixture.name) "allow" else "deny"
            when (val result = evaluate(verified, fixture)) {
                is FixtureEvaluation.Refused -> {
                    out("{\"fixture\": \"${fixture.name}\", \"status\": \"refused\", " +
                        "\"message\": \"${result.message}\"}")
                    exitCode = maxOf(exitCode, ExitCodes.ADMISSION_ERROR)
                }
                is FixtureEvaluation.Error -> {
                    out("{\"fixture\": \"${fixture.name}\", \"status\": \"error\", " +
                        "\"message\": \"${result.message}\"}")
                    exitCode = maxOf(exitCode, ExitCodes.EVALUATION_ERROR)
                }
                is FixtureEvaluation.Evaluated -> {
                    val ok = if (expectation == "allow") result.violations == 0 else result.violations > 0
                    if (!ok) exitCode = maxOf(exitCode, ExitCodes.VIOLATIONS)
                    out(
                        "{\"fixture\": \"${fixture.name}\", \"expectation\": \"$expectation\", " +
                            "\"violations\": ${result.violations}, \"status\": \"${if (ok) "pass" else "FAIL"}\"}",
                    )
                }
            }
        }
        return exitCode
    }

    private sealed interface FixtureEvaluation {
        data class Evaluated(val violations: Int) : FixtureEvaluation
        data class Refused(val message: String) : FixtureEvaluation
        data class Error(val message: String) : FixtureEvaluation
    }

    private fun evaluate(
        verified: com.pipelinek.policy.bundle.VerifiedBundle,
        fixture: File,
    ): FixtureEvaluation {
        val decoder = when (fixture.extension.lowercase()) {
            "json" -> com.pipelinek.policy.decoders.json.JsonResourceDecoder()
            "yaml", "yml" -> com.pipelinek.policy.decoders.yaml.YamlResourceDecoder()
            "csv" -> com.pipelinek.policy.decoders.csv.CsvResourceDecoder()
            else -> return FixtureEvaluation.Refused("unsupported fixture format .${fixture.extension}")
        }
        /**
         * Read under the resource budget first. An oversized fixture is refused
         * before decoding: without this the fixture would be slurped whole and
         * only afterwards rejected by the evaluator's row budget, which is a
         * different failure with a different meaning.
         */
        val fixtureBytes = when (val read = BoundedRead.read(fixture, BoundedRead.DEFAULT_RESOURCE_BUDGET)) {
            is BoundedRead.ReadResult.Ok -> read.bytes
            is BoundedRead.ReadResult.Refused ->
                return FixtureEvaluation.Refused("fixture refused: ${read.refusal}")
        }
        val decoded = try {
            decoder.decode(fixtureBytes, DecodeOptions())
        } catch (e: Exception) {
            return FixtureEvaluation.Refused(e.message ?: "fixture could not be decoded")
        }
        return when (decoded) {
            is DecodeResult.Refused -> FixtureEvaluation.Refused(decoded.refusal.code.name)
            is DecodeResult.Ok -> {
                if (decoded.documents.isEmpty()) {
                    return FixtureEvaluation.Refused("fixture produced no resource documents")
                }
                val outcomes = try {
                    decoded.documents.map { doc ->
                        IrRuntimeAdapter.evaluate(verified, doc.root).report.results.values.toList()
                    }
                } catch (e: Exception) {
                    return FixtureEvaluation.Error(e.message ?: "policy evaluation failed")
                }
                val error = outcomes.flatten().filterIsInstance<RuleEvaluation.Error>().firstOrNull()
                if (error != null) {
                    FixtureEvaluation.Error(error.primary.message)
                } else {
                    FixtureEvaluation.Evaluated(
                        outcomes.count { result -> result.any { it is RuleEvaluation.Violated } },
                    )
                }
            }
        }
    }
}
