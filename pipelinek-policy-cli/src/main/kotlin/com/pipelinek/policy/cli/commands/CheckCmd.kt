package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.cli.BoundedRead
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.Finding
import com.pipelinek.policy.cli.FindingState
import com.pipelinek.policy.cli.FindingsEmitter
import com.pipelinek.policy.cli.dedup
import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoder.ResourceDecoder
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.evaluator.RuleKey
import com.pipelinek.policy.kernel.governance.EnforcementInterpreter
import com.pipelinek.policy.kernel.governance.EnforcementMode
import com.pipelinek.policy.kernel.governance.GovernanceContext
import com.pipelinek.policy.kernel.governance.GovernanceTally
import com.pipelinek.policy.kernel.governance.GovernanceVerdict
import com.pipelinek.policy.kernel.governance.PolicyGovernance
import com.pipelinek.policy.kernel.policy.AuthorityRegistry
import com.pipelinek.policy.kernel.policy.LayeredPolicy
import com.pipelinek.policy.kernel.policy.PolicyLayer
import com.pipelinek.policy.kernel.policy.PolicyViolation
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.policy.SupersessionAuthority
import java.io.File
import java.time.Instant

/**
 * M9 · `check --policy <bundle> <resources...> [--format text|json|jsonl]`
 * (REQ-M9-02). Decoder is chosen by file EXTENSION only — no content
 * sniffing. Unknown extension or refused decode is a REFUSAL finding and
 * dominates the exit code (error > violation > ok).
 */
object CheckCmd {

    private val decodersByExt: Map<String, ResourceDecoder> = mapOf(
        "json" to JsonResourceDecoder(),
        "yaml" to YamlResourceDecoder(),
        "yml" to YamlResourceDecoder(),
        "csv" to CsvResourceDecoder(),
    )

    fun run(args: CliArgs, out: (String) -> Unit): Int {
        val policyPath = args.flag("policy")
            ?: run {
                out("check: missing --policy <bundle>")
                return ExitCodes.USAGE
            }
        val format = args.flag("format") ?: "text"
        if (format !in setOf("text", "json", "jsonl")) {
            out("check: unknown --format $format (text|json|jsonl)")
            return ExitCodes.USAGE
        }
        val resources = args.positionals
        if (resources.isEmpty()) {
            out("check: no resources given")
            return ExitCodes.USAGE
        }

        // H1.2 / B4-T10: enforcement is now reachable from the CLI.
        //
        // It used to be hardcoded to ENFORCED, which meant SHADOW existed in the
        // kernel and in the plugin but had no way to be requested here. The
        // governance path is shared now, so the mode travels to it instead of
        // being decided by an adapter.
        //
        // The value is parsed into the CORE enum, never kept as a string: an
        // unrecognised mode is a usage error rather than a silent fallback to
        // ENFORCED, because falling back would report enforced results for a
        // run the user asked to shadow.
        val enforcement = when (val raw = args.flag("enforcement")?.lowercase()) {
            null, "enforced" -> EnforcementMode.ENFORCED
            "shadow" -> EnforcementMode.SHADOW
            else -> {
                out("check: unknown --enforcement $raw (enforced|shadow)")
                return ExitCodes.USAGE
            }
        }

        val bundleFile = File(policyPath)
        if (!bundleFile.isFile) {
            out(FindingsEmitter.text(listOf(Finding.refusal(policyPath, "bundle not found: $policyPath"))))
            return ExitCodes.ADMISSION_ERROR
        }
        val verified = try {
            val _bounded = BoundedRead.readOrReport(bundleFile, "check", out)
            ?: return ExitCodes.ADMISSION_ERROR
            BundleVerifier.verifyPacked(_bounded)
        } catch (e: Exception) {
            out(FindingsEmitter.text(listOf(Finding.refusal(policyPath, "bundle refused: ${e.message}"))))
            return ExitCodes.ADMISSION_ERROR
        }

        // H1.2 / B4-T10: the outer layers and the host's grants.
        //
        // Before this, `check` composed exactly ONE layer — the bundle it was
        // handed — with an EMPTY authority registry and an EMPTY waiver list.
        // That made the CLI agree with the plugin on every single-layer case and
        // still leave `LayerComposer`, `AuthorityRegistry` and `WaiverMatcher`
        // unreachable from the command line. The parity suite could not tell the
        // difference, because on one layer composition is the identity.
        //
        // Both flags are REPEATABLE, because composition is N-ary. A single-value
        // flag would have composed the wrong set while reporting success.
        val outerLayers = args.flagValues("layer")
        val authority = parseGrants(args.flagValues("grant"), out)
            ?: return ExitCodes.USAGE
        val verifiedLayers = outerLayers.mapIndexedNotNull { index, spec ->
            val parsed = parseLayerSpec(spec)
            if (parsed == null) {
                out(
                    "check: --layer '$spec' must be <LAYER>=<bundle>, " +
                        "LAYER in (${PolicyLayer.entries.joinToString(", ") { it.name }})",
                )
                return ExitCodes.USAGE
            }
            val (layer, layerPath) = parsed
            val layerFile = File(layerPath)
            if (!layerFile.isFile) {
                out(FindingsEmitter.text(listOf(Finding.refusal(layerPath, "layer bundle not found: $layerPath"))))
                return ExitCodes.ADMISSION_ERROR
            }
            // Every layer is admitted through the SAME verifier as the main
            // bundle: a layer gets identical terms or none.
            try {
                val bytes = BoundedRead.readOrReport(layerFile, "check layer", out)
                    ?: return ExitCodes.ADMISSION_ERROR
                layer to BundleVerifier.verifyPacked(bytes)
            } catch (e: Exception) {
                out(FindingsEmitter.text(listOf(Finding.refusal(layerPath, "layer refused: ${e.message}"))))
                return ExitCodes.ADMISSION_ERROR
            }
        }

        val checked = resources.map { res -> checkResource(verified, res, out, enforcement, verifiedLayers, authority) }
        val findings = checked.flatMap { it.findings }
        // Every resource contributes to ONE tally: the exit code describes the
        // run, not each file in isolation.
        val tally = checked.fold(CheckedResource.EMPTY) { acc, one -> acc + one }
        val deduped = findings.dedup()
        emit(deduped, format, out)

        // H1.2 / B4-T10: the exit code comes from the CORE verdict, not from counting
        // findings.
        //
        // This used to tally `Finding` states here. Two things were wrong with
        // that. First, a finding is a RENDERING of a rule outcome, so counting
        // them counted a projection rather than the decision: a rule with two
        // violations weighed double. Second, the CLI had no seam for layers,
        // waivers or a host grant at all, so `LayerComposer`, `WaiverMatcher` and
        // `AuthorityRegistry` had zero production callers — a bundle could claim
        // a supersession and the CLI had no way to honour or refuse it.
        //
        // The governance verdict now comes back from the check itself, already
        // resolved through the shared service. The CLI maps it to a frozen exit
        // code and implements no rule.
        val verdict = EnforcementInterpreter.verdict(tally.tally)
        val denies = tally.deniesUnder(enforcement)
        // The exit code follows the CORE verdict, which already accounts for the
        // enforcement mode. SHADOW reports the violation as evidence without
        // failing the command; REFUSED and ERRORED still fail in SHADOW because
        // an operational failure is never shadowable (B0.5).
        return when (verdict) {
            GovernanceVerdict.REFUSED -> ExitCodes.ADMISSION_ERROR
            GovernanceVerdict.ERRORED -> ExitCodes.EVALUATION_ERROR
            GovernanceVerdict.VIOLATED -> if (denies) ExitCodes.VIOLATIONS else ExitCodes.OK
            GovernanceVerdict.PASSED -> ExitCodes.OK
        }
    }

    /**
     * H1.2 / B4-T10 · parse one `--layer <LAYER>=<bundle>` occurrence.
     *
     * Returns `null` rather than throwing: the caller renders the usage error
     * with the full list of legal layers, which a bare exception cannot.
     */
    private fun parseLayerSpec(spec: String): Pair<PolicyLayer, String>? {
        val eq = spec.indexOf('=')
        if (eq <= 0 || eq == spec.length - 1) return null
        val name = spec.substring(0, eq)
        val layer = PolicyLayer.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return layer to spec.substring(eq + 1)
    }

    /**
     * H1.2 / B4-T10 · parse `--grant <issuer>:<LAYER>:<digest>` occurrences.
     *
     * A grant is the HOST saying what it authorizes, so it must come from the
     * command line and never from a bundle.
     *
     * The digest is taken as everything AFTER the second colon rather than by
     * `split(':')`. That is not pedantry: a real digest is
     * `sha256:<hex>`, so splitting on every colon yields FOUR fields and the
     * naive three-field parser rejected every genuine grant while accepting
     * only malformed ones. Parsing the issuer and the layer from the front and
     * treating the remainder as an opaque digest is both correct and the only
     * reading that does not constrain what a digest may contain.
     */
    private fun parseGrants(
        specs: List<String>,
        out: (String) -> Unit,
    ): AuthorityRegistry? {
        val authorities = specs.map { spec ->
            val firstColon = spec.indexOf(':')
            val secondColon = spec.indexOf(':', firstColon + 1)
            if (firstColon <= 0 || secondColon <= firstColon + 1 || secondColon == spec.length - 1) {
                out("check: --grant '$spec' must be <issuer>:<LAYER>:<digest>")
                return null
            }
            val issuer = spec.substring(0, firstColon)
            val layerName = spec.substring(firstColon + 1, secondColon)
            val digest = spec.substring(secondColon + 1)
            val layer = PolicyLayer.entries.firstOrNull { it.name.equals(layerName, ignoreCase = true) }
            if (layer == null) {
                out(
                    "check: --grant '$spec' names unknown layer '$layerName' " +
                        "(legal: ${PolicyLayer.entries.joinToString(", ") { it.name }})",
                )
                return null
            }
            SupersessionAuthority(
                issuer = issuer,
                grantedLayers = setOf(layer),
                grantDigest = digest,
            )
        }
        return AuthorityRegistry.of(*authorities.toTypedArray())
    }

    private fun checkResource(
        verified: com.pipelinek.policy.bundle.VerifiedBundle,
        resourcePath: String,
        out: (String) -> Unit,
        enforcement: EnforcementMode,
        outerLayers: List<Pair<PolicyLayer, com.pipelinek.policy.bundle.VerifiedBundle>> = emptyList(),
        authority: AuthorityRegistry = AuthorityRegistry.EMPTY,
    ): CheckedResource {
        val file = File(resourcePath)
        if (!file.isFile) {
            return CheckedResource(
                findings = listOf(Finding.refusal(resourcePath, "resource not found: $resourcePath")),
                tally = GovernanceTally(refusals = 1),
            )
        }
        val ext = file.extension.lowercase()
        val decoder = decodersByExt[ext]
            ?: return CheckedResource(
                findings = listOf(
                    Finding.refusal(resourcePath, "no decoder for extension .$ext (by-extension only, no sniffing)"),
                ),
                tally = GovernanceTally(refusals = 1),
            )
        val resourceBytes = BoundedRead.readOrReport(
            file,
            "check resource",
            out,
            BoundedRead.DEFAULT_RESOURCE_BUDGET,
        ) ?: return CheckedResource(
            findings = listOf(Finding.refusal(resourcePath, "resource exceeds the ingress budget")),
            tally = GovernanceTally(refusals = 1),
        )
        return when (val decoded = decoder.decode(resourceBytes, DecodeOptions())) {
            is DecodeResult.Refused -> CheckedResource(
                findings = listOf(Finding.refusal(resourcePath, "decode refused: ${decoded.refusal.code}")),
                tally = GovernanceTally(refusals = 1),
            )
            is DecodeResult.Ok -> decoded.documents.fold(CheckedResource.EMPTY) { acc, doc ->
                acc + governDocument(verified, doc, resourcePath, enforcement, outerLayers, authority)
            }
        }
    }

    /**
     * H1.2 / B4-T10 · one document through the shared governance service.
     *
     * The tally is RETURNED by governance rather than re-derived from the
     * findings rendered below. A finding is a projection: dedup collapses
     * repeated occurrences and one rule can emit several violations, so counting
     * findings would let a rendering decision change the exit code. The verdict
     * counts rule outcomes, which is what the exit code means.
     */
    private fun governDocument(
        verified: com.pipelinek.policy.bundle.VerifiedBundle,
        doc: com.pipelinek.policy.decoder.ResourceDocument,
        resourcePath: String,
        enforcement: EnforcementMode,
        outerLayers: List<Pair<PolicyLayer, com.pipelinek.policy.bundle.VerifiedBundle>> = emptyList(),
        authority: AuthorityRegistry = AuthorityRegistry.EMPTY,
    ): CheckedResource {
        val governed = PolicyGovernance.decide(
            layers = outerLayers.map { (layer, bundle) ->
                LayeredPolicy(layer, bundle.bundle.document.policySet)
            } + LayeredPolicy(PolicyLayer.PROJECT, verified.bundle.document.policySet),
            tree = doc.root,
            // Waivers are not on the `check` surface yet (B4-T11). An EMPTY list
            // is the fail-closed reading: nothing is exempted, and the day a
            // waiver flag arrives it flows through this same call rather than a
            // second code path.
            waivers = emptyList(),
            context = GovernanceContext(authorities = authority, now = CLI_WAIVER_CLOCK),
            enforcement = enforcement,
            evaluate = { policySet, node ->
                IrRuntimeAdapter.evaluateSet(policySet, verified.bundle.sourceMap, node).report
            },
        )

        return when (governed) {
            is PolicyGovernance.Result.Refused -> CheckedResource(
                findings = listOf(
                    Finding.refusal(resourcePath, "layer composition refused: ${governed.refusal.code}"),
                ),
                tally = GovernanceTally(refusals = 1),
            )
            is PolicyGovernance.Result.Decided -> {
                val outcome = governed.outcome
                val report = outcome.report
                val findings = report.results.entries.flatMap { (key, evaluation) ->
                    when (evaluation) {
                        is RuleEvaluation.Error -> listOf(
                            findingFor(
                                key = key,
                                resourceId = doc.id.value,
                                path = resourcePath,
                                resourceFingerprint = report.resourceFingerprint,
                                violation = evaluation.primary,
                                isEvaluationError = true,
                                document = doc,
                                bundleDigest = verified.bundle.manifest.artifactDigest,
                            ),
                        )
                        is RuleEvaluation.Violated -> evaluation.violations.map { violation ->
                            findingFor(
                                key = key,
                                resourceId = doc.id.value,
                                path = resourcePath,
                                resourceFingerprint = report.resourceFingerprint,
                                violation = violation,
                                isEvaluationError = false,
                                document = doc,
                                bundleDigest = verified.bundle.manifest.artifactDigest,
                            )
                        }
                        RuleEvaluation.Passed, RuleEvaluation.NotApplicable -> emptyList()
                    }
                }
                CheckedResource(
                    findings = findings,
                    tally = GovernanceTally(
                        errors = report.results.values.count { it is RuleEvaluation.Error },
                        violations = outcome.activeViolations,
                    ),
                )
            }
        }
    }

    /**
     * Findings plus the tally governance produced for them.
     *
     * Both travel together on purpose: a caller that rendered findings without
     * the tally would be pushed back into counting them, which is the divergence
     * this type exists to remove.
     */
    private data class CheckedResource(
        val findings: List<Finding>,
        val tally: GovernanceTally,
    ) {
        /**
         * Merge two results, ADDING the tallies.
         *
         * A multi-document resource is several governed evaluations, and the
         * exit code is decided over all of them at once. Counting is therefore
         * not per-document either: a resource whose first document violates and
         * whose second is refused is REFUSED overall, because nothing about it
         * could be fully evaluated.
         */
        operator fun plus(other: CheckedResource): CheckedResource = CheckedResource(
            findings = findings + other.findings,
            tally = GovernanceTally(
                refusals = tally.refusals + other.tally.refusals,
                errors = tally.errors + other.tally.errors,
                violations = tally.violations + other.tally.violations,
            ),
        )

        /**
         * Whether this run must fail NOW, in the given mode.
         *
         * Asked of the CORE interpreter rather than re-derived here. A CLI that
         * decided `denies` itself would be a second implementation of the rule
         * the plugin also delegates to the core — the exact duplication H1.2
         * removed.
         */
        fun deniesUnder(enforcement: EnforcementMode): Boolean =
            EnforcementInterpreter.denies(EnforcementInterpreter.verdict(tally), enforcement)

        companion object {
            val EMPTY = CheckedResource(emptyList(), GovernanceTally())
        }
    }

    /**
     * Law 5: the kernel reads no clock and this adapter is not the clock owner.
     * `check` exposes no `--now`, and no waiver travels today, so the value is
     * inert; it is pinned rather than read from the wall so that adding a
     * time-scoped waiver cannot quietly introduce ambient time into a decision.
     */
    private val CLI_WAIVER_CLOCK: Instant = Instant.EPOCH

    private fun findingFor(
        key: RuleKey,
        resourceId: String,
        path: String,
        resourceFingerprint: String,
        violation: PolicyViolation,
        isEvaluationError: Boolean,
        document: com.pipelinek.policy.decoder.ResourceDocument?,
        bundleDigest: String,
    ): Finding {
        // B3: resolve the violation's logical path to the document's physical
        // anchor so findings carry line/column, not just the logical path.
        val physical = document?.let {
            com.pipelinek.policy.cli.PhysicalLocator.anchorFor(it, violation.location)
        }
        val line: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.TextSpan)?.startLine
        val column: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.TextSpan)?.startColumn
        val row: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.Cell)?.row
        val col: Long? = (physical as? com.pipelinek.policy.decoder.SourceAnchor.Cell)?.column
        return if (isEvaluationError) {
            Finding.error(
                policyId = key.policyId,
                ruleId = key.ruleId,
                policySetId = key.policySetId,
                resourceId = resourceId,
                path = path,
                message = violation.message,
                resourceFingerprint = resourceFingerprint,
                locationPath = violation.location.toString(),
                bundleDigest = bundleDigest,
                sourceAnchor = physical,
                actual = violation.actual,
                expected = violation.expected,
                line = line ?: row,
                column = column ?: col,
            )
        } else {
            Finding.violation(
                policyId = key.policyId,
                ruleId = key.ruleId,
                policySetId = key.policySetId,
                resourceId = resourceId,
                path = path,
                message = violation.message,
                resourceFingerprint = resourceFingerprint,
                locationPath = violation.location.toString(),
                bundleDigest = bundleDigest,
                sourceAnchor = physical,
                actual = violation.actual,
                expected = violation.expected,
                line = line ?: row,
                column = column ?: col,
            )
        }
    }

    private fun emit(findings: List<Finding>, format: String, out: (String) -> Unit) {
        when (format) {
            "json" -> out(FindingsEmitter.json(findings))
            "jsonl" -> out(FindingsEmitter.jsonl(findings))
            else -> out(FindingsEmitter.text(findings))
        }
    }
}
