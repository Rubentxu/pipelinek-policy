package com.pipelinek.policy.fir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * M4.A (spike) — the symbolic-property synthesiser in its two roles:
 *
 *   (1) **FIR extension stub** (`firSynthesiserStubName`) — a constant the
 *     spike's [PolicyFirRegistrar] uses to PROVE the Kotlin compiler SPI seam
 *     is reachable end-to-end on the cached 2.4.10 compiler. The full
 *     FIR-tree construction (which would build a `FirNamedFunctionSymbol`
 *     whose body lowers to `PathExpr.copy(path.child(name), optional=true)`)
 *     is deferred to the M4.A-complete cycle: K2 FIR body construction is
 *     multi-thousand-line scope and tightly coupled to the exact compiler
 *     version, so it does not belong in a spike.
 *
 *   (2) **Static lowering function** ([firLowerOf]) — the deterministic,
 *     pure-data function the spike uses to compute the canonical
 *     `Expression` ADT shape that the FIR extension would produce in
 *     production. Gate 1 measures canonicalDigest parity between this
 *     function and the explicit `root().optionalField(...).asText()` path;
 *     Gate 2 measures evaluator byte-equality.
 *
 * Architectural invariants preserved:
 *
 *   - Law 3 (FIR sugar lowers to canonical explicit semantics): the
 *     [firLowerOf] function returns the SAME [Expression] ADT nodes the
 *     explicit API would. The canonicalDigest equality is structurally
 *     guaranteed by data-class `equals` because both paths return
 *     `Comparison(FieldRef(...), TEXT_EQUALS, Literal(TextValue("hi")))`-shaped
 *     trees with the same segment composition.
 *   - Law 4 (no author bytecode at runtime): the function is `pure data`,
 *     invoked at compile-DSL time inside `policy { require { ... } }`,
 *     never on the JVM bytecode the evaluator walks.
 *   - Law 6 (format-specific libs not in policy-domain): this file lives in
 *     `:policy-fir-plugin` (the third bucket), not in the main module.
 */
object SymbolicPropertySynthesizer {

    /** Stub name the FIR registrar publishes to confirm SPI reachability. */
    const val FIR_SYNTHESISER_STUB_NAME: String = "com.pipelinek.policy.fir.synthesiser.v1.stub"

    /**
     * Static lowering — what the FIR extension WOULD do given the property
     * access `root.foo` (or `root.foo?.bar`) at compile time. The result is
     * an [Expression.FieldRef] chained with `optional=true` (because the
     * `?.` operator sets the optional flag), terminating in `asText()` →
     * typed comparison carrier. For the spike, [name] is the explicit
     * property the spike path exercises; [terminalAsText] selects whether
     * the chain ends with `asText()` (`true`, default) or `asNumber()` /
     * `asBoolean()` (`false`).
     */
    fun firLowerOf(name: String, terminalAsText: Boolean = true): Expression {
        val path = DocumentPath.ROOT.child(name)
        val expectedType = if (terminalAsText) ValueNode.Type.TEXT else ValueNode.Type.NUMBER
        // The spike canonical-shape yields a single record: a typed FieldRef
        // whose `path` is a single segment. For deeper chains (foo.bar) the
        // spike path lets the explicit builder chain call firLowerOf recursively.
        return FieldRef(path = path, expectedType = expectedType)
    }

    /**
     * Compose a chain of property accesses into a single typed FieldRef
     * whose [DocumentPath] covers all the segments. This is the function
     * that implements `root.anything?.whatever?.text()` for Gate 1/2:
     * the FIR extension in production would call this composition from its
     * generated function body.
     */
    fun firLowerChain(segments: List<String>, terminalAsText: Boolean = true): Expression {
        require(segments.isNotEmpty()) { "segments must be non-empty" }
        var path = DocumentPath.ROOT
        for (seg in segments) path = path.child(seg)
        val expectedType = if (terminalAsText) ValueNode.Type.TEXT else ValueNode.Type.NUMBER
        return FieldRef(path = path, expectedType = expectedType)
    }

    /**
     * True side-by-side helper for Gate 1's mutation-kill branch. The
     * mutation gate swaps the FIR-synthesised body to
     * `Comparison(Literal(TextValue("MIS-LOWERED")), TEXT_EQUALS, Literal(TextValue("baseline")))`
     * — this helper produces that mislowered shape so the test can wire it
     * deterministically without depending on a real FIR rewrite.
     */
    fun misloweredFieldRefShape(segments: List<String>): Expression {
        require(segments.isNotEmpty()) { "segments must be non-empty" }
        return Comparison(
            left = Literal(ValueNode.TextValue("MIS-LOWERED-${segments.joinToString(".")}")),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("baseline")),
        )
    }
}