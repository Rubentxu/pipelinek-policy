package com.pipelinek.policy.kernel.expression

import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"Policy/Rule and pure evaluator" + M3 §"DSL symbolic API".
 *
 * The expression ADT is the canonical IR that the DSL lowers to and the
 * evaluator walks. M1 ships three shapes (`Literal`, `FieldRef`, `Comparison`)
 * plus six numeric comparison operators. M3 ADDS three shapes in an
 * additive-only fashion (architectural law 12: lower policy layers MUST NOT
 * silently weaken mandatory upper-layer rules):
 *
 *   - `CollectionPredicate(op, source, predicate)` — cursor over a
 *     `SequenceValue`/`MappingValue` with `op ∈ {ALL, ANY, NONE, COUNT}`.
 *     `predicate` is a `Selector` (NOT an author lambda — law 4).
 *   - `Operator.TEXT_EQUALS` / `Operator.BOOLEAN_EQUALS` — two new
 *     comparison operators for textual / boolean operands, evaluated
 *     structurally (law 9: no silent coercion).
 *
 * The `sealed interface` is preserved; existing M1 variants stay closed
 * and `Comparison` stays the carrier for both numeric AND text/boolean
 * equality depending on the static type of the operands.
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

    /**
     * Spec REQ §"DSL rule combinators" — parameter reference. The DSL produces
     * this node when the author writes `ref("min")`; the
     * `com.pipelinek.policy.dsl.ParamSubstitutor` walks the expression tree and
     * replaces every `Reference` with the corresponding `Literal(ParamValue)`
     * BEFORE handing the `Rule` to the kernel. The kernel evaluator MUST NOT
     * see a `Reference` (it would emit `TYPE_MISMATCH` — a defensive default).
     * This data class lives in the kernel sealed hierarchy so it can substitute
     * transparently into `Comparison.left`/`right` without an `Any?` escape
     * hatch (architectural law 7).
     */
    data class Reference(val name: String) : Expression {
        override val displayName: String get() = "Reference(\$$name)"
    }

    /**
     * Spec REQ §"DSL collection predicates" — cursor over a collection
     * `source` (`SequenceValue` or `MappingValue`) with a `Selector`
     * predicate. `op` discriminates the verdict shape. `Missing` on the
     * source short-circuits deterministically without traversing the
     * remaining segments (spec REQ §"Missing short-circuits deterministically").
     */
    data class CollectionPredicate(
        val op: CollectionOp,
        val source: Expression,
        val predicate: Selector,
    ) : Expression {
        override val displayName: String get() = "CollectionPredicate($op)"
    }

    /** Comparison operators. Distinct values guard the GTE/GT mutation gate. */
    enum class Operator {
        EQ,
        NEQ,
        GT,
        GTE,
        LT,
        LTE,
        // M3 additions: structural equality for Text and Boolean operands.
        // Both are evaluated structurally (==) and NEVER coerce to Number
        // (architectural law 9 + spec REQ §"TextEquals and BooleanEquals close
        // the ADT"). Back-compat: numeric GTE/GT/EQ/... remain unchanged.
        TEXT_EQUALS,
        BOOLEAN_EQUALS,
    }

    /**
     * M8 addition (additive-only, like the M3 shapes): a reference to a
     * NAMED DATASET. Inside a bounded `CollectionPredicate` it means
     * AGGREGATE (single pass, capped); free-floating it means GLOBAL
     * (law 12: only legal with an explicit index plan).
     */
    data class DatasetRef(val name: String) : Expression {
        override val displayName: String get() = "DatasetRef($name)"
    }

    /** Collection predicate ops. The verdict shape per op is fixed. */
    enum class CollectionOp {
        ALL,
        ANY,
        NONE,
        COUNT,
    }
}
