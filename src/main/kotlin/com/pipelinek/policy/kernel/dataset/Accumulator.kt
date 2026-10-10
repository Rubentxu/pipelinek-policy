package com.pipelinek.policy.kernel.dataset

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

/** Budget refusal vocabulary, retained for the row-budget path (law 8: distinct from type). */
class BudgetExceededException(message: String) : RuntimeException(message)

/** Type refusal inside SUM. Maps to TYPE_MISMATCH (no silent coercion). */
class SumTypeMismatchException(message: String) : RuntimeException(message)

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
