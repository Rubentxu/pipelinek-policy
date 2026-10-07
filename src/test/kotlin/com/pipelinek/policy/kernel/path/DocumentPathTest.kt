package com.pipelinek.policy.kernel.path

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Spec REQ §"ValueTree and Selector" — DocumentPath.
 *
 * `DocumentPath` is the stable identity for a node inside a `ValueTree`. It
 * MUST be content-canonical (insertion-order independent across the tree)
 * and MUST render to a human-readable string for diagnostics.
 */
class DocumentPathTest {

    @Test
    fun `empty path is the root and equal only to itself`() {
        val root = DocumentPath.ROOT
        assertEquals(DocumentPath.ROOT, root)
        assertEquals("", root.toString())
    }

    @Test
    fun `child segments compose left to right`() {
        val p = DocumentPath.ROOT.child("spec").child("replicas")
        assertEquals("spec.replicas", p.toString())
    }

    @Test
    fun `segments are exposed in order for deterministic iteration`() {
        val p = DocumentPath.ROOT.child("a").child("b").child("c")
        assertEquals(listOf("a", "b", "c"), p.segments)
    }

    @Test
    fun `paths with identical segments are equal regardless of construction order`() {
        val left = DocumentPath.ROOT.child("spec").child("replicas")
        val right = DocumentPath.ROOT.child("spec").child("replicas")
        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun `paths with different segments are not equal`() {
        val left = DocumentPath.ROOT.child("spec").child("replicas")
        val right = DocumentPath.ROOT.child("spec").child("image")
        assertNotEquals(left, right)
    }

    @Test
    fun `parent pops the last segment`() {
        val p = DocumentPath.ROOT.child("spec").child("replicas")
        assertEquals(DocumentPath.ROOT.child("spec"), p.parent())
    }

    @Test
    fun `parent of root returns root`() {
        assertEquals(DocumentPath.ROOT, DocumentPath.ROOT.parent())
    }

    @Test
    fun `length matches the segment count`() {
        assertEquals(0, DocumentPath.ROOT.length)
        assertEquals(2, DocumentPath.ROOT.child("spec").child("replicas").length)
    }
}
