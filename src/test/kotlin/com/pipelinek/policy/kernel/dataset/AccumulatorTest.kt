package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
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
        assertEquals(ValueNode.NumberValue(6.5), acc.result())
    }

    @Test
    fun `04b - falsification - count cap refuses instead of folding forever`() {
        val acc = Accumulator.Count(cap = 2)
        acc.accept(ValueNode.TextValue("a"))
        acc.accept(ValueNode.TextValue("b"))
        val ex = assertThrows<BudgetExceededException> { acc.accept(ValueNode.TextValue("c")) }
        assertTrue(ex.message!!.contains("cap 2"))
        // State is NOT corrupted past the refusal: still 2.
        assertEquals(ValueNode.NumberValue(2L), acc.result())
    }

    @Test
    fun `04b - falsification - sum refuses text rows instead of coercing`() {
        val acc = Accumulator.Sum(cap = 10)
        acc.accept(ValueNode.NumberValue(1))
        val ex = assertThrows<SumTypeMismatchException> {
            acc.accept(ValueNode.TextValue("7"))
        }
        assertTrue(ex.message!!.contains("TEXT"))
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
