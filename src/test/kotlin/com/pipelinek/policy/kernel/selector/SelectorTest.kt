package com.pipelinek.policy.kernel.selector

import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.kernel.value.ValueNode.BooleanValue
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.Missing
import com.pipelinek.policy.kernel.value.ValueNode.Null
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Spec REQ §"ValueTree and Selector" — Selector contract.
 *
 * Three outcomes:
 *   - `Present(node)`     — the node exists and matches the expected type.
 *   - `Missing(path)`     — the path does not exist (NOT the same as Null).
 *   - `TypeMismatch(path, expected, actual)` — the leaf exists but is the wrong type.
 *
 * Laws:
 *   - Missing MUST stay distinct from Null (architectural law 8).
 *   - Text("3") MUST NOT coerce to Number(3) under a Number selector.
 *   - Selector resolution is total: it never throws.
 */
class SelectorTest {

    private val tree: ValueNode = MappingValue(
        linkedMapOf(
            "spec" to MappingValue(
                linkedMapOf(
                    "replicas" to NumberValue(3),
                    "image" to TextValue("nginx:1.27"),
                    "gate" to Null,
                ),
            ),
            "metadata" to MappingValue(
                linkedMapOf(
                    "name" to TextValue("hello"),
                    "labels" to MappingValue(linkedMapOf("env" to TextValue("dev"))),
                ),
            ),
        ),
    )

    @Test
    fun `Spec UAT (a) selector spec_replicas resolves to Present Number 3`() {
        val result = Selector.of(DocumentPath.ROOT.child("spec").child("replicas"))
            .resolve(tree)
        assertEquals(Selector.Result.Present(NumberValue(3)), result)
    }

    @Test
    fun `Spec UAT (b) selector on absent path resolves to Missing distinct from Null`() {
        val absent = Selector.of(DocumentPath.ROOT.child("spec").child("replicas").child("image"))
            .resolve(tree)
        assertEquals(
            Selector.Result.Missing(DocumentPath.ROOT.child("spec").child("replicas").child("image")),
            absent,
        )
        // Spec scenario: "Missing stays distinct from Null"
        assertEquals(Missing, ValueNode.Missing)
    }

    @Test
    fun `Spec UAT (c) selector on spec_gate resolves to Present Null`() {
        val result = Selector.of(DocumentPath.ROOT.child("spec").child("gate"))
            .resolve(tree)
        assertEquals(Selector.Result.Present(Null), result)
    }

    @Test
    fun `Spec UAT (d) selector on spec_image with Number expectation returns TypeMismatch`() {
        val result = Selector.of(DocumentPath.ROOT.child("spec").child("image"))
            .expectingType(ValueNode.Type.NUMBER)
            .resolve(tree)
        assertEquals(
            Selector.Result.TypeMismatch(
                path = DocumentPath.ROOT.child("spec").child("image"),
                expected = ValueNode.Type.NUMBER,
                actual = ValueNode.Type.TEXT,
            ),
            result,
        )
    }

    @Test
    fun `selector with Number expectation on NumberValue returns Present`() {
        val result = Selector.of(DocumentPath.ROOT.child("spec").child("replicas"))
            .expectingType(ValueNode.Type.NUMBER)
            .resolve(tree)
        assertEquals(Selector.Result.Present(NumberValue(3)), result)
    }

    @Test
    fun `selector with Text expectation on TextValue returns Present`() {
        val result = Selector.of(DocumentPath.ROOT.child("metadata").child("name"))
            .expectingType(ValueNode.Type.TEXT)
            .resolve(tree)
        assertEquals(Selector.Result.Present(TextValue("hello")), result)
    }

    @Test
    fun `selector with Number expectation on Null returns TypeMismatch (Null is not Number)`() {
        val result = Selector.of(DocumentPath.ROOT.child("spec").child("gate"))
            .expectingType(ValueNode.Type.NUMBER)
            .resolve(tree)
        assertEquals(
            Selector.Result.TypeMismatch(
                DocumentPath.ROOT.child("spec").child("gate"),
                ValueNode.Type.NUMBER,
                ValueNode.Type.NULL,
            ),
            result,
        )
    }

    @Test
    fun `nested mapping selector resolves leaves`() {
        val labels = DocumentPath.ROOT.child("metadata").child("labels").child("env")
        val result = Selector.of(labels).resolve(tree)
        assertEquals(Selector.Result.Present(TextValue("dev")), result)
    }

    @Test
    fun `selector on a Sequence element by index is supported`() {
        val seq = MappingValue(linkedMapOf("list" to ValueNode.SequenceValue(listOf(NumberValue(7)))))
        val r = Selector.of(DocumentPath.ROOT.child("list").child("0"))
            .expectingType(ValueNode.Type.NUMBER)
            .resolve(seq)
        assertEquals(Selector.Result.Present(NumberValue(7)), r)
    }

    @Test
    fun `selector on out-of-range sequence index returns Missing`() {
        val seq = MappingValue(linkedMapOf("list" to ValueNode.SequenceValue(listOf(NumberValue(7)))))
        val r = Selector.of(DocumentPath.ROOT.child("list").child("5")).resolve(seq)
        assertEquals(
            Selector.Result.Missing(DocumentPath.ROOT.child("list").child("5")),
            r,
        )
    }

    @Test
    fun `boolean leaf is its own type with No coercion to Text`() {
        val t = MappingValue(linkedMapOf("flag" to BooleanValue(true)))
        val r = Selector.of(DocumentPath.ROOT.child("flag"))
            .expectingType(ValueNode.Type.TEXT)
            .resolve(t)
        assertEquals(
            Selector.Result.TypeMismatch(
                DocumentPath.ROOT.child("flag"),
                ValueNode.Type.TEXT,
                ValueNode.Type.BOOLEAN,
            ),
            r,
        )
    }
}
