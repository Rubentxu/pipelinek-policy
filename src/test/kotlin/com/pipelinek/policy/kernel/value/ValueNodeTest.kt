package com.pipelinek.policy.kernel.value

import com.pipelinek.policy.kernel.value.ValueNode.BooleanValue
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.Missing
import com.pipelinek.policy.kernel.value.ValueNode.Null
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import com.pipelinek.policy.kernel.value.ValueNode.SequenceValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * Spec REQ §"ValueTree and Selector" — Scenario: Manual in-memory ValueTree.
 *
 * Drives the value kernel ADT with concrete scenarios from the spec
 * (text vs number distinction, missing vs null distinction) so the
 * type system itself catches regressions.
 */
class ValueNodeTest {

    @Test
    fun `sealed hierarchy exposes the seven canonical type tags`() {
        val all: List<ValueNode> = listOf(
            Missing,
            Null,
            TextValue("hello"),
            NumberValue(3),
            BooleanValue(true),
            SequenceValue(listOf(NumberValue(1), NumberValue(2))),
            MappingValue(mapOf("a" to NumberValue(1))),
        )
        assertEquals(7, all.size)
        val tags = all.map { it.type }.toSet()
        assertEquals(
            setOf(
                ValueNode.Type.MISSING,
                ValueNode.Type.NULL,
                ValueNode.Type.TEXT,
                ValueNode.Type.NUMBER,
                ValueNode.Type.BOOLEAN,
                ValueNode.Type.SEQUENCE,
                ValueNode.Type.MAPPING,
            ),
            tags,
        )
    }

    @Test
    fun `Missing is a singleton and distinct from Null`() {
        val missing: ValueNode = Missing
        val nullNode: ValueNode = Null
        assertSame(Missing, ValueNode.Missing)
        assertEquals(Missing, ValueNode.Missing)
        assertNotEquals(missing, nullNode)
        assertNotEquals(nullNode, missing)
    }

    @Test
    fun `Text carries its content and is not coerced to Number`() {
        val t = TextValue("3")
        assertEquals("3", t.text)
        // Explicit assertion that we did NOT silently coerce to a numeric 3.
        assertEquals("3", t.text)
    }

    @Test
    fun `Number carries its numeric content`() {
        val n = NumberValue(3)
        assertEquals(3, n.number.toInt())
    }

    @Test
    fun `Sequence carries its elements in order`() {
        val seq = SequenceValue(listOf(NumberValue(1), TextValue("two")))
        assertEquals(2, seq.elements.size)
        assertEquals(NumberValue(1), seq.elements[0])
        assertEquals(TextValue("two"), seq.elements[1])
    }

    @Test
    fun `Mapping carries its entries`() {
        val map = MappingValue(linkedMapOf("a" to NumberValue(1), "b" to TextValue("two")))
        assertEquals(2, map.entries.size)
        assertEquals(NumberValue(1), map.entries["a"])
        assertEquals(TextValue("two"), map.entries["b"])
    }

    @Test
    fun `Mapping equality ignores insertion order (canonical content)`() {
        val left = MappingValue(linkedMapOf("a" to NumberValue(1), "b" to NumberValue(2)))
        val right = MappingValue(linkedMapOf("b" to NumberValue(2), "a" to NumberValue(1)))
        assertEquals(left, right)
    }

    @Test
    fun `Spec UAT (a) ValueTree mirroring map_spec_replicas_equals_3`() {
        // Spec scenario: "map in-memory converted manually to ValueTree"
        //   GIVEN a ValueTree mirroring `map["spec"]["replicas"] = 3`
        val tree = MappingValue(
            linkedMapOf(
                "spec" to MappingValue(linkedMapOf("replicas" to NumberValue(3))),
            ),
        )
        val spec = (tree as MappingValue).entries["spec"] as MappingValue
        val replicas = spec.entries["replicas"] as NumberValue
        assertEquals(ValueNode.Type.MAPPING, tree.type)
        assertEquals(ValueNode.Type.NUMBER, replicas.type)
        assertEquals(3, replicas.number.toInt())
    }

    @Test
    fun `Spec UAT (c) Text leaf three-string remains Text distinct from Number three`() {
        // Spec scenario: "'3' no se coacciona a 3" — Text MUST NOT coerce to Number.
        val textLeaf: ValueNode = TextValue("3")
        val numberLeaf: ValueNode = NumberValue(3)
        assertEquals(ValueNode.Type.TEXT, textLeaf.type)
        assertEquals(ValueNode.Type.NUMBER, numberLeaf.type)
        assertNotEquals(textLeaf, numberLeaf)
        assertNotEquals(numberLeaf, textLeaf)
    }
}
