package com.pipelinek.policy.kernel.expression

import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Spec REQ §"Policy/Rule and pure evaluator" — Expression ADT.
 *
 * Minimal expression ADT needed to express the canonical UAT rule
 *   `spec.replicas asNumber gte 3`
 * and the mutation gate (GTE->GT).
 */
class ExpressionTest {

    @Test
    fun `Literal carries a ValueNode unchanged`() {
        val literal = Expression.Literal(ValueNode.NumberValue(3))
        assertEquals(ValueNode.NumberValue(3), (literal as Expression.Literal).value)
    }

    @Test
    fun `FieldRef carries a path and an expected type tag`() {
        val fr = Expression.FieldRef(
            DocumentPath.ROOT.child("spec").child("replicas"),
            ValueNode.Type.NUMBER,
        )
        assertEquals(DocumentPath.ROOT.child("spec").child("replicas"), fr.path)
        assertEquals(ValueNode.Type.NUMBER, fr.expectedType)
    }

    @Test
    fun `Comparison holds left op right with op including GTE`() {
        val c = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            Expression.Operator.GTE,
            Expression.Literal(ValueNode.NumberValue(3)),
        )
        assertEquals(Expression.Operator.GTE, c.op)
        assertNotEquals(Expression.Operator.GT, c.op)
    }

    @Test
    fun `Operator enum covers the comparison alphabet and distinguishes GTE vs GT`() {
        // M3 ADDS Operator.TEXT_EQUALS / BOOLEAN_EQUALS (additive only).
        val ops = Expression.Operator.values().toSet()
        assertEquals(
            setOf(
                Expression.Operator.EQ,
                Expression.Operator.NEQ,
                Expression.Operator.GT,
                Expression.Operator.GTE,
                Expression.Operator.LT,
                Expression.Operator.LTE,
                Expression.Operator.TEXT_EQUALS,
                Expression.Operator.BOOLEAN_EQUALS,
            ),
            ops,
        )
        assertNotEquals(Expression.Operator.GT, Expression.Operator.GTE)
    }

    @Test
    fun `expression equality is structural`() {
        val left = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("x"), ValueNode.Type.NUMBER),
            Expression.Operator.GTE,
            Expression.Literal(ValueNode.NumberValue(3)),
        )
        val right = Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("x"), ValueNode.Type.NUMBER),
            Expression.Operator.GTE,
            Expression.Literal(ValueNode.NumberValue(3)),
        )
        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }
}
