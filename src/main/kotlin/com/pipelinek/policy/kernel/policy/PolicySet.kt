package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath

/**
 * Spec REQ §"Policy/Rule and pure evaluator" — PolicySet / Policy / Rule IR.
 *
 * All shapes are immutable `data` types so structural equality is automatic and
 * the PolicyReport can fingerprint the IR deterministically.
 */

data class PolicySet(
    val id: String,
    val policies: List<Policy>,
)

data class Policy(
    val id: String,
    val rules: List<Rule>,
)

data class Rule(
    val id: String,
    val message: String,
    val expression: Expression,
)

/**
 * Outcome of evaluating a single `Rule` over a `ValueTree`. Sealed so the
 * evaluator never returns an unexpected shape (architectural law 5: pure).
 */
sealed interface RuleEvaluation {

    val violations: List<PolicyViolation>

    data object Passed : RuleEvaluation {
        override val violations: List<PolicyViolation> get() = emptyList()
    }

    data class Violated(
        override val violations: List<PolicyViolation>,
    ) : RuleEvaluation

    data object NotApplicable : RuleEvaluation {
        override val violations: List<PolicyViolation> get() = emptyList()
    }

    data class Error(
        val primary: PolicyViolation,
    ) : RuleEvaluation {
        override val violations: List<PolicyViolation> get() = listOf(primary)
    }
}

/**
 * Spec REQ §"PolicyReport and canonical value hashing" — violation record.
 *
 * `code` is one of `ViolationCode` (typed refusal). `expected` and `actual`
 * are stringified renderings for diagnostics; both nullable because not all
 * violations have both (e.g. a missing path has no actual value to report).
 */
data class PolicyViolation(
    val code: ViolationCode,
    val location: DocumentPath,
    val message: String,
    val expected: String? = null,
    val actual: String? = null,
)

enum class ViolationCode {
    MISSING_REQUIRED_VALUE,
    TYPE_MISMATCH,
    COMPARISON_FAILED,
}
