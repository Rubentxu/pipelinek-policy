package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.value.ValueNode

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

    /** Fold one row value into the accumulator. */
    fun accept(value: ValueNode)

    /** Current verdict materialized as a ValueNode (never mutates state). */
    fun result(): ValueNode

    /** Count of rows folded so far (for BudgetMetrics). */
    val rowsSeen: Long

    /** Count-based accumulator (COUNT op). */
    class Count(private val cap: Long) : Accumulator {
        private var count: Long = 0
        override val rowsSeen: Long get() = count

        init {
            require(cap >= 0) { "count cap must be >= 0" }
        }

        override fun accept(value: ValueNode) {
            check(count < cap) {
                throw BudgetExceededException("count $count reached cap $cap")
            }
            // Count does not inspect content: any node counts as one row.
            @Suppress("UNUSED_EXPRESSION")
            value
            count++
        }

        override fun result(): ValueNode = ValueNode.NumberValue(count)
    }

    /** Numeric sum accumulator (SUM op). TYPE_MISMATCH on non-number rows. */
    class Sum(private val cap: Long) : Accumulator {
        private var total: Double = 0.0
        private var exact: Long = 0
        private var seen: Long = 0
        override val rowsSeen: Long get() = seen

        init {
            require(cap >= 0) { "sum cap must be >= 0" }
        }

        override fun accept(value: ValueNode) {
            check(seen < cap) {
                throw BudgetExceededException("sum $seen rows reached cap $cap")
            }
            when (value) {
                is ValueNode.NumberValue -> {
                    total += value.number.toDouble()
                    exact += value.number.toLong()
                    seen++
                }
                else -> throw SumTypeMismatchException(
                    "SUM only admits NumberValue rows, got ${value.type}",
                )
            }
        }

        override fun result(): ValueNode = ValueNode.NumberValue(total)
    }
}

/** Budget refusal: the accumulator hit its hard cap. Maps to a BUDGET_EXCEEDED violation. */
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
