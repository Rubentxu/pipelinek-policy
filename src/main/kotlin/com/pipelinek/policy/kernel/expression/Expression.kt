package com.pipelinek.policy.kernel.expression

import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"Policy/Rule and pure evaluator" — expression ADT.
 *
 * The minimal expression surface needed for the M1 UAT (`spec.replicas gte 3`)
 * and the mutation gate (replace GTE with GT). The ADT is closed under
 * structural equality so the evaluator can compare IR fingerprints
 * deterministically.
 *
 * Three shapes:
 *   - `Literal(node)` — a direct value used as a constant.
 *   - `FieldRef(path, expectedType)` — a reference to a leaf in the input tree.
 *   - `Comparison(left, op, right)` — a binary comparison.
 *
 * Six comparison operators:
 *   `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`.
 */
sealed interface Expression {

    val displayName: String

    /** Literal value. */
    data class Literal(val value: ValueNode) : Expression {
        override val displayName: String get() = "Literal"
    }

    /** Reference to a leaf at `path` with declared `expectedType`. */
    data class FieldRef(
        val path: DocumentPath,
        val expectedType: ValueNode.Type,
    ) : Expression {
        override val displayName: String get() = "FieldRef(${path})[$expectedType]"
    }

    /** Binary comparison. */
    data class Comparison(
        val left: Expression,
        val op: Operator,
        val right: Expression,
    ) : Expression {
        override val displayName: String get() = "Comparison($left $op $right)"
    }

    /** Comparison operators. Distinct values guard the GTE/GT mutation gate. */
    enum class Operator {
        EQ,
        NEQ,
        GT,
        GTE,
        LT,
        LTE,
    }
}
