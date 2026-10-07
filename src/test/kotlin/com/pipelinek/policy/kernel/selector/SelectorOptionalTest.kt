package com.pipelinek.policy.kernel.selector

import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.kernel.value.ValueNode.BooleanValue
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec REQ §"DSL symbolic API" — M3 selector `optional` flag.
 *
 * Mutation gate item 4: an `optionalField` that maps `Missing` to `Null`
 * MUST be detectable. The M3 design keeps `Missing` as `Selector.Result.Missing`
 * (NOT `Null`, NOT `Present(false)`), so the DSL can short-circuit deterministically
 * — see the spec §"Missing short-circuits deterministically" scenario.
 */
class SelectorOptionalTest {

    private val tree: ValueNode = MappingValue(
        linkedMapOf(
            "spec" to MappingValue(
                linkedMapOf("replicas" to NumberValue(3)),
            ),
        ),
    )

    @Test
    fun `optional selector preserves Missing on absent path`() {
        val s = Selector.optional(DocumentPath.ROOT.child("spec").child("missing"))
        val r = s.resolve(tree)
        assertEquals(
            Selector.Result.Missing(DocumentPath.ROOT.child("spec").child("missing")),
            r,
        )
        // Optional selectors report `isRequired()` = false.
        assertFalse(s.isRequired())
    }

    @Test
    fun `optional selector returns Present on present path`() {
        val s = Selector.optional(DocumentPath.ROOT.child("spec").child("replicas"))
            .expectingType(ValueNode.Type.NUMBER)
        val r = s.resolve(tree)
        assertEquals(Selector.Result.Present(NumberValue(3)), r)
        assertTrue(s.isRequired() == false)
    }

    @Test
    fun `optional selector still surfaces TypeMismatch (not Missing)`() {
        val s = Selector.optional(DocumentPath.ROOT.child("spec")).expectingType(ValueNode.Type.BOOLEAN)
        val r = s.resolve(tree)
        assertEquals(
            Selector.Result.TypeMismatch(
                DocumentPath.ROOT.child("spec"),
                ValueNode.Type.BOOLEAN,
                ValueNode.Type.MAPPING,
            ),
            r,
        )
    }

    @Test
    fun `asOptional promotes a required selector to optional`() {
        val s = Selector.of(DocumentPath.ROOT.child("spec").child("missing"))
            .asOptional()
        assertFalse(s.isRequired())
        val r = s.resolve(tree)
        assertEquals(
            Selector.Result.Missing(DocumentPath.ROOT.child("spec").child("missing")),
            r,
        )
    }

    @Test
    fun `required selector by default reports isRequired=true`() {
        val s = Selector.of(DocumentPath.ROOT.child("spec").child("replicas"))
        assertTrue(s.isRequired())
    }

    @Test
    fun `optional selector on Boolean leaf returns Present`() {
        val t = MappingValue(linkedMapOf("flag" to BooleanValue(true)))
        val s = Selector.optional(DocumentPath.ROOT.child("flag"))
            .expectingType(ValueNode.Type.BOOLEAN)
        val r = s.resolve(t)
        assertEquals(Selector.Result.Present(BooleanValue(true)), r)
    }

    @Test
    fun `optional selector on Text leaf returns Present`() {
        val t = MappingValue(linkedMapOf("name" to TextValue("hello")))
        val s = Selector.optional(DocumentPath.ROOT.child("name"))
            .expectingType(ValueNode.Type.TEXT)
        val r = s.resolve(t)
        assertEquals(Selector.Result.Present(TextValue("hello")), r)
    }
}
