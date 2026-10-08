package com.pipelinek.policy.fir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath

/**
 * M4.A (spike, tasks 2.5) — the static, deterministic oracle the spike
 * tests use to compute the canonical-AST digest the FIR extension would
 * produce. The function takes a dotted property chain (the segments of
 * `root.anything.whatever`) and returns the same [Expression] ADT node the
 * explicit DSL path would build:
 *
 *   firLowerOf("anything.whatever") ≡ Comparison(
 *       left = FieldRef(path = anything.whatever, expectedType = TEXT),
 *       op   = TEXT_EQUALS,
 *       right = Literal(TextValue("hello")),
 *   )
 *
 * The shape is byte-equal to:
 *
 *   policy {
 *     require {
 *         root().optionalField("anything").optionalField("whatever").asText()
 *             .equals(string("hello"))
 *       }
 *   }
 *
 * so the [com.pipelinek.policy.kernel.value.canonicalDigest] computed
 * over the produced `PolicySet` matches across the FIR-sugar path and the
 * explicit path (Gate 1).
 *
 * The function is intentionally `compileOnly`-shaped (no JVM bytecode the
 * evaluator walks), so the production runtime never executes author-supplied
 * code (architectural law 4).
 */
object PathExprFirSupport {

    /**
     * Convert a dotted property chain into the typed FieldRef the FIR
     * extension would have synthesised at compile time.
     *
     * @param dottedPath e.g. `"anything.whatever"` for the chain
     *   `root.anything?.whatever?.text()`.
     * @return the [Expression.FieldRef] (terminal-as-text) the lowerer
     *   would produce.
     */
    fun firLowerOf(dottedPath: String): Expression {
        val segments = dottedPath.split('.').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "dottedPath must contain at least one segment" }
        val path: DocumentPath = segments.fold(DocumentPath.ROOT) { acc, seg -> acc.child(seg) }
        return SymbolicPropertySynthesizer.firLowerChain(segments, terminalAsText = true)
            .let { expr ->
                // Rebuild through the path so we exercise the DocumentPath
                // child() chain explicitly (mirrors what the explicit DSL
                // path does under optionalField()).
                require(expr is com.pipelinek.policy.kernel.expression.Expression.FieldRef) {
                    "firLowerChain must produce a FieldRef"
                }
                com.pipelinek.policy.kernel.expression.Expression.FieldRef(
                    path = path,
                    expectedType = expr.expectedType,
                )
            }
    }

    /**
     * Convenience: builds a `Comparison(..., TEXT_EQUALS, Literal("hello"))`
     * on top of [firLowerOf] so Gate 1 + Gate 2 can compare the rule shape
     * across FIR-sugar / explicit / mislowered paths.
     */
    fun ruleForGate1(dottedPath: String, terminalLiteral: String = "hello"): Expression {
        val fieldRef = firLowerOf(dottedPath)
        return com.pipelinek.policy.kernel.expression.Expression.Comparison(
            left = fieldRef,
            op = com.pipelinek.policy.kernel.expression.Expression.Operator.TEXT_EQUALS,
            right = com.pipelinek.policy.kernel.expression.Expression.Literal(
                com.pipelinek.policy.kernel.value.ValueNode.TextValue(terminalLiteral),
            ),
        )
    }
}
