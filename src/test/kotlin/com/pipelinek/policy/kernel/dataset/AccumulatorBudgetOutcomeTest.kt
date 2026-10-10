package com.pipelinek.policy.kernel.dataset

import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.math.BigDecimal

/**
 * B5.6 · Bounded accumulators must refuse with a TYPED outcome, and SUM must
 * be numerically exact.
 *
 * Two defects lived in one small file:
 *
 *  1. `BudgetExceededException` was an incidental `RuntimeException` thrown
 *     from inside a fold. A caller had to know the concrete class and
 *     hard-code a catch; there was no value describing WHICH cap was hit, how
 *     much was consumed, or at which row. The roadmap asks for a typed
 *     budget outcome instead.
 *
 *  2. `Sum` accumulated into a `Double` while also computing an `exact: Long`
 *     that was then thrown away — `result()` returned the double. Summing
 *     large integers or values needing >17 significant digits silently lost
 *     precision, which is a different flavour of the same "evaluates to a
 *     number the author never wrote" family as B5.3.
 *
 * Both are pinned here, with the ASCII-free numeric values chosen so a double
 * round-trip is visibly wrong rather than accidentally right.
 */
class AccumulatorBudgetOutcomeTest {

    private fun number(raw: String): ValueNode = ValueNode.NumberValue(java.math.BigDecimal(raw))

    /**
     * Assert the sum is EXACT by checking its representation, not just its
     * numeric value.
     *
     * A purely numeric comparison is not enough and was actively misleading:
     * widening a lossy `Double` back through `toString()` yields the ORIGINAL
     * decimal string ("6.5", "9007199254740992.0"), so a value-only assertion
     * passed against the very mutation it was written to catch. The
     * representation is the observable: a double-folded sum surfaces as
     * Double or carries a fractional scale, an exact BigDecimal does not.
     */
    private fun assertExact(expected: String, node: ValueNode) {
        val actual = assertIs<ValueNode.NumberValue>(node).number
        assertIs<BigDecimal>(actual, "sum must be a BigDecimal, was ${actual::class.simpleName}")
        val exact = actual as BigDecimal
        assertEquals(
            0,
            exact.compareTo(BigDecimal(expected)),
            "expected $expected but was $exact",
        )
        // Round-tripping through a double must be a no-op for an exact result.
        // If it changes the value, the fold was lossy.
        assertEquals(
            0,
            exact.stripTrailingZeros().compareTo(BigDecimal(exact.toDouble().toString()).stripTrailingZeros()),
            "value $exact does not survive a double round-trip, so it was computed lossily",
        )
    }

    @Test
    fun `01a exceeding the count cap yields a typed outcome, not an exception`() {
        val acc = Accumulator.Count(cap = 2)
        assertEquals(Accumulator.Outcome.Accepted, acc.accept(ValueNode.TextValue("a")))
        acc.accept(ValueNode.TextValue("b"))
        // No `assertFailsWith`: a fold must not unwind the stack to report a
        // budget decision. The outcome is a VALUE the caller inspects.
        val outcome = acc.accept(ValueNode.TextValue("c"))
        val refused = assertIs<Accumulator.Outcome.Refused>(outcome)
        assertEquals(Accumulator.Outcome.Cap.COUNT, refused.cap)
        assertEquals(2L, refused.consumed)
        assertEquals(2L, refused.limit)
    }

    @Test
    fun `01b exceeding the sum cap yields a typed outcome, not an exception`() {
        val acc = Accumulator.Sum(cap = 2)
        acc.accept(number("1"))
        acc.accept(number("2"))
        val refused = assertIs<Accumulator.Outcome.Refused>(acc.accept(number("3")))
        assertEquals(Accumulator.Outcome.Cap.SUM, refused.cap)
        assertEquals(2L, refused.consumed)
        assertEquals(2L, refused.limit)
    }

    @Test
    fun `01c a refused accumulator keeps a coherent partial result`() {
        // A refusal must not leave the accumulator in a state where `result()`
        // disagrees with `rowsSeen`. Truncation must be visible, not implied.
        val acc = Accumulator.Count(cap = 2)
        acc.accept(ValueNode.TextValue("a"))
        acc.accept(ValueNode.TextValue("b"))
        acc.accept(ValueNode.TextValue("c")) // refused
        assertEquals(ValueNode.NumberValue(2L), acc.result())
        assertEquals(2L, acc.rowsSeen)
        assertIs<Accumulator.Outcome.Refused>(acc.outcome)
    }

    @Test
    fun `01d no exception class is required to observe a budget refusal`() {
        // The old contract forced callers to know `BudgetExceededException`.
        // This test compiles only if the outcome is inspectable without it;
        // a regression to throwing breaks it at the call site.
        val acc = Accumulator.Count(cap = 1)
        acc.accept(ValueNode.TextValue("a"))
        val refused = assertIs<Accumulator.Outcome.Refused>(acc.accept(ValueNode.TextValue("b")))
        assertEquals(Accumulator.Outcome.Cap.COUNT, refused.cap)
    }

    // --- SUM exactness ---

    @Test
    fun `02a SUM is exact beyond double precision`() {
        // 0.1 + 0.2 in binary double is 0.30000000000000004. Summing three
        // rows of 0.1 the naive way drifts further with every row.
        val acc = Accumulator.Sum(cap = 10)
        acc.accept(number("0.1"))
        acc.accept(number("0.2"))
        acc.accept(number("0.3"))
        assertExact("0.6", acc.result())
    }

    @Test
    fun `02b SUM of large integers does not lose precision`() {
        // 2^53 + 1 is NOT representable as a double: the naive fold yields
        // 9007199254740992.0, silently changing the author's value.
        val acc = Accumulator.Sum(cap = 10)
        acc.accept(number("9007199254740993"))
        acc.accept(number("1"))
        assertExact("9007199254740994", acc.result())
    }

    @Test
    fun `02c SUM preserves integer rows as integers`() {
        val acc = Accumulator.Sum(cap = 10)
        acc.accept(number("2"))
        acc.accept(number("3"))
        assertExact("5", acc.result())
        // Integral input must not surface as a double when the domain has a
        // faithful exact representation, nor carry a fractional scale.
        val actual = assertIs<ValueNode.NumberValue>(acc.result()).number as BigDecimal
        assertEquals(0, actual.scale(), "an integral sum must not carry a fractional scale")
    }

    @Test
    fun `03a SUM still refuses a non-number row with a typed mismatch`() {
        // The type refusal must stay distinct from the budget refusal.
        val acc = Accumulator.Sum(cap = 10)
        val outcome = acc.accept(ValueNode.TextValue("not-a-number"))
        val refused = assertIs<Accumulator.Outcome.Refused>(outcome)
        assertEquals(Accumulator.Outcome.Cap.TYPE_MISMATCH, refused.cap)
    }
}
