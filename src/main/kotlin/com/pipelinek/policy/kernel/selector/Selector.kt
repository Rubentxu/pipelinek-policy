package com.pipelinek.policy.kernel.selector

import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"ValueTree and Selector" — path-based selector.
 *
 * Resolves a `DocumentPath` against a `ValueNode` tree and yields one of
 * three outcomes (sealed):
 *   - `Present(node)` — the path resolves to a node.
 *   - `Missing(path)` — the path does not resolve (NOT the same as Null).
 *   - `TypeMismatch(path, expected, actual)` — the path resolves but the
 *     resolved node has a type other than the expected one (when set).
 *
 * M3 ADDS an `optional` flag (default `false`):
 *   - `optional = false` (required, M1 behaviour): `Missing` ⇒ `MissingValueException`
 *     in the evaluator ⇒ `RuleEvaluation.Violated(MISSING_REQUIRED_VALUE)`.
 *   - `optional = true`: `Missing` is preserved as `Result.Missing` so the
 *     caller (DSL combinators or collection predicates) can short-circuit
 *     deterministically (e.g. `appliesWhen(false)` ⇒ `NotApplicable`,
 *     `count` ⇒ `0`, `any` ⇒ `false`).
 *   - In NEITHER case is `Missing` mapped to `Null` — architectural law 8.
 *
 * Laws:
 *   - Missing is distinct from Null (architectural law 8).
 *   - Text is never coerced to Number (architectural law 9).
 *   - Resolution is total: it never throws, even for malformed trees.
 */
class Selector private constructor(
    private val path: DocumentPath,
    private val expected: ValueNode.Type?,
    private val optional: Boolean,
) {

    /** Constrain the selector to require a specific leaf type. */
    fun expectingType(type: ValueNode.Type): Selector =
        Selector(path, type, optional)

    /** Mark the selector as optional: a `Missing` result is preserved (not raised). */
    fun asOptional(): Selector = Selector(path, expected, optional = true)

    /** Resolves the path against `root` and returns one of three outcomes. */
    fun resolve(root: ValueNode): Result {
        // Reduce segments over the current node; `null` short-circuits to Missing.
        val reached: ValueNode? = path.segments.fold(root as ValueNode?) { acc, segment ->
            acc?.let { descend(it, segment) }
        }
        return reached?.let { finalize(it) } ?: Result.Missing(path)
    }

    /**
     * M3 hook: callers that need to know whether they should raise on `Missing`
     * use this. Returns `true` when `Missing` should bubble up as
     * `MissingValueException` (M1 behaviour); `false` when the caller is OK
     * with the `Missing` outcome being preserved as data.
     */
    fun isRequired(): Boolean = !optional

    private fun descend(node: ValueNode, segment: String): ValueNode? = when (node) {
        is ValueNode.MappingValue -> node.entries[segment]
        is ValueNode.SequenceValue -> segment.toIntOrNull()
            ?.takeIf { it in node.elements.indices }
            ?.let { node.elements[it] }
        else -> null
    }

    private fun finalize(node: ValueNode): Result =
        if (expected != null && node.type != expected) {
            Result.TypeMismatch(path, expected, node.type)
        } else {
            Result.Present(node)
        }

    sealed interface Result {

        data class Present(val node: ValueNode) : Result

        data class Missing(val path: DocumentPath) : Result

        data class TypeMismatch(
            val path: DocumentPath,
            val expected: ValueNode.Type,
            val actual: ValueNode.Type,
        ) : Result
    }

    companion object {
        /** New selector for a path (required by default; back-compat with M1). */
        fun of(path: DocumentPath): Selector = Selector(path, null, optional = false)

        /** M3: build an optional selector directly (short-circuit friendly). */
        fun optional(path: DocumentPath): Selector = Selector(path, null, optional = true)
    }
}
