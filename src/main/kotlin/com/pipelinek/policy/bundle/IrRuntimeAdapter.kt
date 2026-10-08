package com.pipelinek.policy.bundle

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.PolicyReport
import com.pipelinek.policy.kernel.value.ValueNode

data class RuntimeEvaluation(val report: PolicyReport, val sourceMap: PolicySourceMap)

object IrRuntimeAdapter {
    fun evaluate(verified: VerifiedBundle, tree: ValueNode): RuntimeEvaluation = RuntimeEvaluation(
        Evaluator.evaluate(verified.bundle.document.policySet, tree),
        verified.bundle.sourceMap,
    )
}
