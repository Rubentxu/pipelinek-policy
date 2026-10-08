package com.pipelinek.policy.m6

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import java.util.Base64

/**
 * M6 UAT fixture packer: builds the canonical packed policy bundle for the
 * pass/violate UAT scripts and prints it Base64 to stdout (one line).
 *
 * Rule (same shape as M5UatAcceptanceTest): spec.replicas >= 3.
 * pass resource: replicas=4 · violate resource: replicas=2.
 */
fun main() {
    val set = PolicySet(
        "uat",
        listOf(
            Policy(
                "p",
                listOf(
                    Rule(
                        "r", "replicas must be >= 3",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("spec").child("replicas"),
                                ValueNode.Type.NUMBER,
                            ),
                            Expression.Operator.GTE,
                            Expression.Literal(ValueNode.NumberValue(3)),
                        ),
                    ),
                ),
            ),
        ),
    )
    val bytes = PolicyBundle(lower(set)).pack()
    println(Base64.getEncoder().encodeToString(bytes))
}
