package com.pipelinek.policy.cli

import com.pipelinek.policy.decoder.NodeId
import com.pipelinek.policy.decoder.ResourceDocument
import com.pipelinek.policy.decoder.SourceAnchor
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * B3 — physical-source resolution for findings.
 *
 * A [com.pipelinek.policy.kernel.policy.PolicyViolation] carries a LOGICAL
 * [DocumentPath]; decoders carry PHYSICAL [SourceAnchor]s keyed by [NodeId]s
 * assigned in pre-order parse order. This resolver walks the document tree in
 * the same pre-order the decoders use, counts ids identically, stops at the
 * node addressed by the logical path, and returns its physical anchor.
 *
 * Lives in the CLI (not the kernel): the kernel never depends on decoder
 * types (architectural law 6) and the mapping logical→physical is a
 * presentation concern of the findings contract.
 */
object PhysicalLocator {

    /**
     * Resolves the physical anchor of the node at [path] inside [document].
     * Returns `null` when the path does not address an existing node (e.g. a
     * MISSING_REQUIRED_VALUE violation whose path never materialized) — the
     * caller then falls back to the logical path alone.
     *
     * The walk visits EVERY node in pre-order (the decoders assign ids in
     * parse order across the whole tree, not per-branch), tracking each
     * node's dotted path to recognize the target.
     */
    fun anchorFor(document: ResourceDocument, path: DocumentPath): SourceAnchor? {
        val target = path.segments.toList()
        var counter = 0L
        var resolved: NodeId? = null

        fun walk(node: ValueNode, here: List<String>) {
            val id = NodeId(counter++)
            if (here == target && resolved == null) resolved = id
            when (node) {
                is ValueNode.MappingValue ->
                    node.entries.forEach { (k, v) -> walk(v, here + k) }
                is ValueNode.SequenceValue ->
                    node.elements.forEachIndexed { i, v -> walk(v, here + i.toString()) }
                else -> Unit
            }
        }

        walk(document.root, emptyList())
        return resolved?.let { document.sourceMap.of(it) }
    }
}
