package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.policy.PolicySet

/**
 * Spec REQ §"DSL purity macro prototype" — the `policy { ... }` macro helper.
 *
 * This is an `inline fun` with a `context(BuilderCtx) PolicySetBuilder.() -> Unit`
 * lambda body. The macro MUST be implementable WITHOUT a Kotlin compiler-plugin
 * (architectural law 3) and MUST NOT capture author lambdas on the produced ADT
 * (architectural law 4).
 *
 * The class graph of the produced `PolicySet` is restricted to:
 *   - `com.pipelinek.policy.kernel.*`
 *   - `com.pipelinek.policy.dsl.*`
 *
 * Spec §"Macro parity with data-built PolicySet": the canonical digest of the
 * macro-produced `PolicySet` is bit-identical to a data-built one. Verified by
 * `ParityTest.target of reference lowers to canonical AST with identical digest`.
 *
 * Spec §"Macro artifact is pure": reachable class set is a subset of the
 * kernel + dsl packages. Verified by `ParityTest.DSL-produced class graph
 * is restricted to kernel and dsl packages`.
 *
 * Spec §"Context capability ambiguity": the lambda receiver is tagged
 * `context(BuilderCtx)` so builders outside this scope do NOT compile
 * (Kotlin 2.x context parameters are checked at compile time).
 *
 * Implementation note: Kotlin's `inline fun` requires every member it touches
 * (constructors, methods, fields) to be public. `PolicySetBuilder` /
 * `PolicyBuilder` / `RuleBuilder` are public so the macro can call them; the
 * public surface is intentional — those builders ARE the author-facing API.
 */
inline fun policy(
    id: String = "policy-set",
    block: context(BuilderCtx) PolicySetBuilder.() -> Unit,
): PolicySet {
    val builder = PolicySetBuilder(id)
    // The user's lambda is `context(BuilderCtx) PolicySetBuilder.() -> Unit`.
    // We invoke it by entering both receivers — BuilderCtx as the context
    // parameter, and PolicySetBuilder as the extension receiver.
    BuilderCtx.root().run {
        builder.block()
    }
    return builder.toPolicySet()
}
