package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath

/**
 * Spec REQ §"Policy/Rule and pure evaluator" — PolicySet / Policy / Rule IR.
 *
 * All shapes are immutable `data` types so structural equality is automatic and
 * the PolicyReport can fingerprint the IR deterministically.
 *
 * M3 ADDS (additive-only; back-compat with M1):
 *   - `Rule.appliesWhen: Expression?` (default null): if non-null and evaluates
 *     to false / Missing / TypeMismatch, the rule short-circuits to
 *     `RuleEvaluation.NotApplicable` (NOT `Violated`). Spec §"appliesWhen(false)
 *     yields NotApplicable" REQ-Rule-Combinators.
 *   - `Rule.code` / `expected` / `actual`: typed metadata propagated to the
 *     `PolicyViolation` without string parsing (architectural law 9 + spec
 *     §"Rule carries violation metadata as data").
 *   - `Rule.params: Map<String, ParamValue>` (default empty): compile-DSL
 *     substitution data, never re-resolved by the kernel.
 *   - `ViolationCode.COLLECTION_PREDICATE_FAILED`: emitted by the
 *     `CollectionPredicate` evaluator branch.
 *
 * M7 ADDS (additive-only; back-compat with M1/M3/M5/M6):
 *   - `Rule.supersession: Supersession?` (default null): explicit layer
 *     supersession metadata consumed by `LayerComposer` (spec §3). The
 *     evaluator ignores it; bundles packed before M7 deserialize to null.
 */

data class PolicySet(
    val id: String,
    val policies: List<Policy>,
) {
    init {
        require(policies.map { it.id }.distinct().size == policies.size) {
            "policySet $id contains duplicate policy ids"
        }
        policies.forEach { policy ->
            require(policy.rules.map { it.id }.distinct().size == policy.rules.size) {
                "policy ${policy.id} contains duplicate rule ids"
            }
        }
    }
}

data class Policy(
    val id: String,
    val rules: List<Rule>,
)

data class Rule(
    val id: String,
    val message: String,
    val expression: Expression,
    val appliesWhen: Expression? = null,
    val code: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val params: Map<String, ParamValue> = emptyMap(),
    val supersession: Supersession? = null,
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

/**
 * Ordinals of the first three values (0..2) MUST stay stable — M1 tests and
 * the canonical PolicyReport digest rely on them. M3 appends
 * `COLLECTION_PREDICATE_FAILED` without reordering.
 */
enum class ViolationCode {
    MISSING_REQUIRED_VALUE,
    TYPE_MISMATCH,
    COMPARISON_FAILED,
    // M3: emitted by CollectionPredicate when the predicate verdict (per op)
    // fails (per-element type mismatch, element missing, or ALL/ANY/NONE/COUNT
    // verdict based on the cursor over SequenceValue.elements / MappingValue.entries).
    COLLECTION_PREDICATE_FAILED,
}
