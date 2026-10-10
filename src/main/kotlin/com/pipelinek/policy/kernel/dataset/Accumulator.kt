package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import java.math.BigDecimal

/**
 * M8 WU-2 · Single-pass, bounded accumulators for AGGREGATE-shaped rules.
 *
 * Laws (docs/07 §5):
 *  - The evaluator core performs no I/O; accumulators are pure folds fed by
 *    the StreamingEvaluator row cursor.
 *  - Every accumulator has a hard cap: exceeding it is BUDGET_EXCEEDED
 *    (Violation-style refusal), never an unbounded fold.
 *  - SUM only admits NumberValue elements; anything else is TYPE_MISMATCH
 *    (no silent coercion, law 9).
 */
sealed interface Accumulator {

    /**
     * B5.6 · Result of folding one row. A budget or type decision is a VALUE,
     * not a thrown exception: a fold runs inside the evaluator's hot path and
     * unwinding the stack to report "the cap was reached" made callers depend
     * on the concrete exception class and left no place to carry which cap
     * was hit or how much was consumed.
     */
    sealed interface Outcome {
        /** The row was folded. */
        data object Accepted : Outcome

        /** The row was NOT folded. [cap] says why, with the numbers to explain it. */
        data class Refused(val cap: Cap, val consumed: Long, val limit: Long) : Outcome

        /** Which refusal occurred. Closed set: budget and type stay distinct (law 8). */
        enum class Cap {
            COUNT,
            SUM,
            ROW_BUDGET,
            TYPE_MISMATCH,
            /**
             * B6.6: an `ALL` predicate whose element did not match. This is NOT
             * a refusal of the row: the row was folded correctly, and the rule
             * is simply violated by that element. It is named as a Cap so the
             * closed set stays closed without forcing the two to be conflated.
             */
            PREDICATE_FAILED,
        }
    }

    /** The most recent outcome, or null before the first row. */
    val outcome: Outcome?

    /** Fold one row value into the accumulator, returning the typed outcome. */
    fun accept(value: ValueNode): Outcome

    /** Current verdict materialized as a ValueNode (never mutates state). */
    fun result(): ValueNode

    /** Count of rows folded so far (for BudgetMetrics). */
    val rowsSeen: Long

    /** Count-based accumulator (COUNT op). */
    class Count(private val cap: Long) : Accumulator {
        private var count: Long = 0
        override val rowsSeen: Long get() = count
        override var outcome: Outcome? = null
            private set

        init {
            require(cap >= 0) { "count cap must be >= 0" }
        }

        override fun accept(value: ValueNode): Outcome {
            if (count >= cap) {
                // B5.6: refuse as a value and leave the fold untouched, so
                // result() and rowsSeen stay consistent with what was folded.
                val refused = Outcome.Refused(Outcome.Cap.COUNT, count, cap)
                outcome = refused
                return refused
            }
            // Count does not inspect content: any node counts as one row.
            @Suppress("UNUSED_EXPRESSION")
            value
            count++
            outcome = Outcome.Accepted
            return Outcome.Accepted
        }

        override fun result(): ValueNode = ValueNode.NumberValue(count)
    }

    /**
     * B6.6 · Boolean collection accumulator (ALL / ANY / NONE ops).
     *
     * These three ops produce a BOOLEAN, not a number, so folding them through
     * [Sum] was always wrong: `Sum` admits only `NumberValue`, so an `ANY` over
     * a text column was refused as TYPE_MISMATCH on every row and reported as
     * `Error(type_mismatch ...)`. They are a genuine single-pass fold — they
     * inspect every row — so they cannot simply be skipped either; skipping
     * removed the rule from the report altogether.
     *
     * Semantics are deliberately identical to `Evaluator.evalCollectionPredicate`
     * so the two evaluation paths cannot drift:
     *  - element matches when [Selector.resolve] is Present, or Missing on an
     *    OPTIONAL selector; TypeMismatch never matches (law 9, no coercion);
     *  - `ALL` short-circuits to `false` at the first non-matching element;
     *  - `ANY` short-circuits to `true` at the first matching element;
     *  - `NONE` short-circuits to `false` at the first matching element;
     *  - an EMPTY source leaves `ALL` and `NONE` true and `ANY` false, because
     *    they are built from `none {}` / `any {}` / an empty loop.
     *
     * [shortCircuited] records whether the verdict became final early, so the
     * caller can stop paying for rows it cannot change the answer of.
     */
    class BooleanFold(
        private val op: Expression.CollectionOp,
        private val predicate: Selector,
        private val cap: Long,
    ) : Accumulator {
        private var seen: Long = 0
        private var verdict: kotlin.Boolean = when (op) {
            Expression.CollectionOp.ALL, Expression.CollectionOp.NONE -> true
            else -> false
        }
        private var decided = false

        override val rowsSeen: Long get() = seen
        override var outcome: Outcome? = null
            private set

        /** True once the verdict is final and further rows cannot change it. */
        val shortCircuited: kotlin.Boolean get() = decided

        init {
            require(op != Expression.CollectionOp.COUNT) { "COUNT is folded by Accumulator.Count" }
            require(cap >= 0) { "boolean cap must be >= 0" }
        }

        override fun accept(value: ValueNode): Outcome {
            // A decided verdict is still consuming the cap: the rows ARE being
            // read, so refusing them keeps `rowsRefused` honest instead of
            // pretending the stream was shorter than it was.
            if (decided || seen >= cap) {
                return Outcome.Refused(Outcome.Cap.COUNT, seen, cap).also { outcome = it }
            }
            val elementMatches = matchesAt(value)
            seen++
            when (op) {
                Expression.CollectionOp.ALL -> if (!elementMatches) {
                    verdict = false
                    decided = true
                }
                Expression.CollectionOp.ANY -> if (elementMatches) {
                    verdict = true
                    decided = true
                }
                Expression.CollectionOp.NONE -> if (elementMatches) {
                    verdict = false
                    decided = true
                }
                Expression.CollectionOp.COUNT -> error("rejected in init")
            }
            return Outcome.Accepted.also { outcome = it }
        }

        /** The materialized verdict. This is the value a rule is satisfied by. */
        override fun result(): ValueNode = ValueNode.BooleanValue(verdict)

        /**
         * Mirror of `Evaluator.matchesAtLocation`: the ROW is the element, so
         * the predicate resolves against the row itself. A mapping row resolves
         * directly; a scalar row is wrapped under `"value"`, which is what the
         * whole-document path does for a scalar collection entry.
         */
        private fun matchesAt(row: ValueNode): kotlin.Boolean {
            val rooted: ValueNode =
                if (row is ValueNode.MappingValue) row else ValueNode.MappingValue(linkedMapOf("value" to row))
            return when (predicate.resolve(rooted)) {
                is Selector.Result.Present -> true
                is Selector.Result.Missing -> !predicate.isRequired()
                is Selector.Result.TypeMismatch -> false
            }
        }
    }

    /** Numeric sum accumulator (SUM op). TYPE_MISMATCH on non-number rows. */
    class Sum(private val cap: Long) : Accumulator {
        // B5.6: exact arithmetic. The previous `Double` total lost precision
        // past 2^53 and drifted on decimal fractions, and the `exact: Long`
        // that was computed alongside it was discarded unused. BigDecimal
        // makes both classes of loss unrepresentable.
        private var total: BigDecimal = BigDecimal.ZERO
        private var seen: Long = 0
        override val rowsSeen: Long get() = seen
        override var outcome: Outcome? = null
            private set

        init {
            require(cap >= 0) { "sum cap must be >= 0" }
        }

        override fun accept(value: ValueNode): Outcome {
            // B5.6: refusal first, so a full accumulator never type-checks a
            // row it cannot fold.
            val overCap = seen >= cap
            val notNumber = value !is ValueNode.NumberValue
            return when {
                overCap -> Outcome.Refused(Outcome.Cap.SUM, seen, cap).also { outcome = it }
                // Law 9: no silent coercion. The type refusal stays a
                // DISTINCT outcome from the budget refusal (law 8).
                notNumber -> Outcome.Refused(Outcome.Cap.TYPE_MISMATCH, seen, cap).also { outcome = it }
                else -> {
                    total = total.add(toBigDecimal((value as ValueNode.NumberValue).number))
                    seen++
                    Outcome.Accepted.also { outcome = it }
                }
            }
        }

        override fun result(): ValueNode = ValueNode.NumberValue(total)

        /**
         * Widen any [Number] the domain may carry without going through a
         * double, which would reintroduce exactly the loss this fixes.
         */
        private fun toBigDecimal(number: Number): BigDecimal = when (number) {
            is BigDecimal -> number
            is java.math.BigInteger -> BigDecimal(number)
            is Long -> BigDecimal.valueOf(number)
            is Int -> BigDecimal.valueOf(number.toLong())
            is Short -> BigDecimal.valueOf(number.toLong())
            is Byte -> BigDecimal.valueOf(number.toLong())
            is Float -> BigDecimal.valueOf(number.toDouble())
            is Double -> BigDecimal.valueOf(number)
            else -> BigDecimal(number.toString())
        }
    }
}

/**
 * Budget accounting for one streaming evaluation run. Pure data; the
 * StreamingEvaluator fills it, callers read it. No clock, no I/O.
 */
data class BudgetMetrics(
    val rowsConsumed: Long,
    val rowsRefused: Long,
    val budgetLimit: Long,
    val accumulatorsUsed: Int,
) {
    val rowsWithinBudget: Boolean get() = rowsConsumed <= budgetLimit

    companion object {
        val EMPTY = BudgetMetrics(0, 0, 0, 0)
    }
}
