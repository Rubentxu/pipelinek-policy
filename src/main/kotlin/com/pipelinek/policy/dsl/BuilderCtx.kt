package com.pipelinek.policy.dsl

/**
 * Spec REQ §"DSL symbolic API" — context parameter carrier for the DSL.
 *
 * ADR-0005 mandates context parameters (Kotlin 2.x) as the way to delimit
 * the DSL surface; `BuilderCtx` is the receiver of `context(BuilderCtx)`-tagged
 * builders. The context carries:
 *
 *   - `pathStack`: a List<String> used to compose `DocumentPath` for nested
 *     `field("a").field("b")` calls and to give clear error messages. The DSL
 *     does NOT pre-evaluate fields against any tree; it ONLY composes paths.
 *   - `params`: a `Map<String, DslParamValue>` of declared `params(...)`. The
 *     DSL substitutes `${name}` references during `policy { ... }` execution
 *     so the resulting `Rule.expression` is purely data.
 *
 * What `BuilderCtx` does NOT carry (per ADR-0005):
 *   - No `PolicyContext` / omnipotent state. `BuilderCtx` is build-time only.
 *   - No I/O. The evaluator never sees it.
 *   - No reflection. The DSL is a static, type-checked function literal.
 *
 * Per spec §"Context capability ambiguity": builders outside the
 * `policy { ... }` lambda do NOT compile because their `context(BuilderCtx)`
 * receiver cannot be satisfied.
 */
data class BuilderCtx(
    val pathStack: List<String>,
    val params: Map<String, DslParamValue>,
) {

    /** Push a path segment onto the current builder context. */
    fun push(segment: String): BuilderCtx = copy(pathStack = pathStack + segment)

    /** Merge params into the current builder context. */
    fun withParams(p: Map<String, DslParamValue>): BuilderCtx = copy(params = p)

    /** Current composed `DocumentPath` derived from the stack. */
    fun path(): com.pipelinek.policy.kernel.path.DocumentPath {
        var p = com.pipelinek.policy.kernel.path.DocumentPath.ROOT
        for (s in pathStack) p = p.child(s)
        return p
    }

    companion object {
        /** Initial builder context (root, no params). */
        fun root(): BuilderCtx = BuilderCtx(emptyList(), emptyMap())
    }
}
