package com.pipelinek.policy.bundle

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.value.ValueNode

data class RuntimeEvaluation(val report: PolicyReport, val sourceMap: PolicySourceMap)

object IrRuntimeAdapter {
    fun evaluate(verified: VerifiedBundle, tree: ValueNode): RuntimeEvaluation = RuntimeEvaluation(
        Evaluator.evaluate(verified.bundle.document.policySet, tree),
        verified.bundle.sourceMap,
    )

    /**
     * H1.2 / B4-T10: evaluate an EXPLICIT policy set, keeping the source map of
     * the bundle it came from.
     *
     * This exists because layered composition produces a policy set that is not
     * `verified.bundle.document.policySet`. An adapter that accepted only the
     * convenience overload above would be structurally unable to evaluate a
     * composed set: it would have to pass the raw bundle back and silently
     * discard the composition. That is precisely the defect H1.2 exists to
     * remove, so the seam is made explicit here rather than worked around in
     * each adapter.
     *
     * The source map is still the bundle's: composition reorders and merges
     * rules, it does not invent new source text, so anchors resolve against the
     * same document the rules were parsed from.
     */
    fun evaluateSet(policySet: PolicySet, sourceMap: PolicySourceMap, tree: ValueNode): RuntimeEvaluation =
        RuntimeEvaluation(Evaluator.evaluate(policySet, tree), sourceMap)
}
