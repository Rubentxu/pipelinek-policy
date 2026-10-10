package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8 REQ 04/05 · Accumulators with caps + BudgetMetrics (04a/04b/05x). */
class AccumulatorTest {

    @Test
    fun `04a - count accumulates and materializes as NumberValue`() {
        val acc = Accumulator.Count(cap = 10)
        repeat(5) { acc.accept(ValueNode.TextValue("row-$it")) }
        assertEquals(ValueNode.NumberValue(5L), acc.result())
        assertEquals(5L, acc.rowsSeen)
    }

    @Test
    fun `04a - sum accumulates numbers`() {
        val acc = Accumulator.Sum(cap = 10)
        acc.accept(ValueNode.NumberValue(1))
        acc.accept(ValueNode.NumberValue(2.5))
        acc.accept(ValueNode.NumberValue(3))
        // B5.6: the sum is now BigDecimal, so compare numerically rather than
        // by object identity — a Double 6.5 would no longer be equal.
        val result = assertIs<ValueNode.NumberValue>(acc.result())
        assertEquals(0, result.number.toString().toBigDecimal().compareTo("6.5".toBigDecimal()))
    }

    @Test
    fun `04b - falsification - count cap refuses instead of folding forever`() {
        val acc = Accumulator.Count(cap = 2)
        acc.accept(ValueNode.TextValue("a"))
        acc.accept(ValueNode.TextValue("b"))
        // B5.6: the cap is now a typed VALUE, not a thrown exception.
        val refused = assertIs<Accumulator.Outcome.Refused>(acc.accept(ValueNode.TextValue("c")))
        assertEquals(Accumulator.Outcome.Cap.COUNT, refused.cap)
        assertEquals(2L, refused.limit)
        // State is NOT corrupted past the refusal: still 2.
        assertEquals(ValueNode.NumberValue(2L), acc.result())
    }

    @Test
    fun `04b - falsification - sum refuses text rows instead of coercing`() {
        val acc = Accumulator.Sum(cap = 10)
        acc.accept(ValueNode.NumberValue(1))
        // B5.6: the type refusal is a distinct typed outcome (law 8), not a
        // separate exception class the caller had to know about.
        val refused = assertIs<Accumulator.Outcome.Refused>(acc.accept(ValueNode.TextValue("7")))
        assertEquals(Accumulator.Outcome.Cap.TYPE_MISMATCH, refused.cap)
        assertEquals(1L, acc.rowsSeen, "the refused row must not be folded")
    }

    @Test
    fun `05a - budget metrics is plain data with derived budget verdict`() {
        val m = BudgetMetrics(rowsConsumed = 5, rowsRefused = 1, budgetLimit = 5, accumulatorsUsed = 2)
        assertTrue(m.rowsWithinBudget)
        val over = m.copy(rowsConsumed = 6)
        assertFalse(over.rowsWithinBudget)
        assertEquals(BudgetMetrics(0, 0, 0, 0), BudgetMetrics.EMPTY)
    }
}
