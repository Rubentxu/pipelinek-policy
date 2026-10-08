package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.ParamValue as KernelParamValue

/**
 * Spec REQ §"DSL rule combinators" — `require { }`, `forbid { }`,
 * `appliesWhen { }`, `params(…)`, `code(…)`, `message(…)`, `expected(…)`,
 * `actual(…)`. The DSL collapses each builder call into a `Rule`/`Policy`/
 * `PolicySet` data-class from the kernel.
 *
 * Every builder is `context(BuilderCtx)`-tagged so it can only be invoked from
 * inside a `policy { ... }` lambda (spec §"Context capability ambiguity").
 *
 * Spec §"Params are substituted at compile-DSL time": `params(mapOf("min" to 3))`
 * pre-substitutes any `${min}` reference in the rule body so the resulting
 * `Rule.expression` carries no runtime param map. There is no `Function0`
 * closure retained (law 4).
 *
 * Spec §"Rule carries violation metadata as data": `code(...)`, `message(...)`,
 * `expected(...)`, `actual(...)` are fields on the `Rule` data carrier. They
 * are propagated to `PolicyViolation` without string parsing.
 */
class RuleBuilder(
    private val id: String,
    private val initialCtx: BuilderCtx,
) {
    private var body: Expression? = null
    private var applies: Expression? = null
    private var params: Map<String, DslParamValue> = initialCtx.params
    private var code: String? = null
    private var message: String = "rule $id"
    private var expected: String? = null
    private var actual: String? = null

    /** `require { expr }` — the rule body. If `expr` evaluates to false ⇒
     *  `Violated`; otherwise `Passed`. The DSL does NOT modify the author-supplied
     *  message; the kernel reads `Rule.message` verbatim and propagates it to
     *  `PolicyViolation`. */
    context(ctx: BuilderCtx)
    fun require(block: context(BuilderCtx) () -> Expression) {
        this.body = block()
    }

    /** `forbid { expr }` — semantically the negated body (truthy ⇒ Violated).
     *  Implemented by inverting the boolean at evaluation time via the
     *  `forbid` flag. The DSL does NOT modify the author-supplied message. */
    private var forbid: Boolean = false
    context(ctx: BuilderCtx)
    fun forbid(block: context(BuilderCtx) () -> Expression) {
        this.body = block()
        this.forbid = true
    }

    /** `appliesWhen { expr }` — gate. If expr is falsy / Missing / TypeMismatch
     *  ⇒ `NotApplicable` (per spec §"appliesWhen(false) yields NotApplicable"). */
    context(ctx: BuilderCtx)
    fun appliesWhen(block: context(BuilderCtx) () -> Expression) {
        this.applies = block()
    }

    /** `params(mapOf("min" to 3))` — declare params. The DSL substitutes
     *  `${name}` references in the rule body before handing the rule to the
     *  kernel. */
    fun params(p: Map<String, DslParamValue>) {
        this.params = (this.params + p).toMap()
    }

    fun code(c: String) {
        this.code = c
    }

    fun message(m: String) {
        this.message = m
    }

    fun expected(e: String) {
        this.expected = e
    }

    fun actual(a: String) {
        this.actual = a
    }

    /** Build the kernel `Rule`. Applies forbid inversion (returns a negated
     *  Comparison or wraps the expression) and param substitution. */
    internal fun toRule(): Rule {
        val resolvedBody = body ?: error("rule $id has no body")
        val substituted = ParamSubstitutor.substitute(resolvedBody, params)
        val finalExpr = if (forbid) {
            // Negate the body by wrapping in EQ against `boolean(false)`. We
            // use `Comparison(..., EQ, Literal(BooleanValue(false)))` because
            // the kernel evaluator already handles boolean operands. The
            // DSL keeps it data-only — no Closure retained.
            com.pipelinek.policy.kernel.expression.Expression.Comparison(
                left = substituted,
                op = com.pipelinek.policy.kernel.expression.Expression.Operator.EQ,
                right = com.pipelinek.policy.kernel.expression.Expression.Literal(
                    com.pipelinek.policy.kernel.value.ValueNode.BooleanValue(false),
                ),
            )
        } else {
            substituted
        }
        return Rule(
            id = id,
            message = message,
            expression = finalExpr,
            appliesWhen = applies,
            code = code,
            expected = expected,
            actual = actual,
            params = params.mapValues { DslParamValue.toKernel(it.value) },
        )
    }
}

/** Builder for a single `Policy`. */
class PolicyBuilder(private val id: String) {
    private val rules = mutableListOf<Rule>()

    fun rule(id: String, build: RuleBuilder.() -> Unit): Rule {
        val rb = RuleBuilder(id = id, initialCtx = BuilderCtx.root())
        rb.build()
        val r = rb.toRule()
        rules += r
        return r
    }

    /** Compile-DSL: collect params for this policy's rules. Not currently
     *  propagated per-rule; reserved for M5 PolicyIR materialization. */
    internal fun toPolicy(): Policy = Policy(id = id, rules = rules.toList())
}

/** Builder for a `PolicySet`. */
class PolicySetBuilder(private val id: String) {
    private val policies = mutableListOf<Policy>()
    private var defaultCounter: Int = 0

    fun policy(id: String, build: PolicyBuilder.() -> Unit): Policy {
        val pb = PolicyBuilder(id = id)
        pb.build()
        val p = pb.toPolicy()
        policies += p
        return p
    }

    /** Convenience: `rule(id, ...)` with an auto-incrementing default id. */
    fun rule(id: String = "rule${++defaultCounter}", build: RuleBuilder.() -> Unit): Rule {
        val rb = RuleBuilder(id = id, initialCtx = BuilderCtx.root())
        rb.build()
        val r = rb.toRule()
        policies += Policy(id = "_anon-$id", rules = listOf(r))
        return r
    }

    fun toPolicySet(): PolicySet = PolicySet(id = id, policies = policies.toList())
}

/**
 * Spec §"Params are substituted at compile-DSL time": walk the expression
 * tree and replace every `Expression.Reference(name)` with the corresponding
 * `Literal(ValueNode)`. Pure data — no closure retained.
 *
 * Unresolved references are preserved as-is so the kernel's defensive
 * branch surfaces a deterministic error (instead of silently substituting a
 * default value).
 */
object ParamSubstitutor {

    /** Substitute every `Reference(name)` in `expr` with the matching
     *  `Literal(DslParamValue.toValueNode(v))`. Unresolved names are kept
     *  (the kernel's defensive branch will emit the error). */
    fun substitute(expr: Expression, params: Map<String, DslParamValue>): Expression {
        return walk(expr, params)
    }

    private fun walk(expr: Expression, params: Map<String, DslParamValue>): Expression = when (expr) {
        is Expression.Literal -> expr
        is Expression.FieldRef -> expr
        is Expression.Reference -> {
            val p = params[expr.name]
            if (p != null) {
                Expression.Literal(DslParamValue.toValueNode(p))
            } else {
                expr
            }
        }
        is Expression.Comparison -> {
            val l = walk(expr.left, params)
            val r = walk(expr.right, params)
            if (l === expr.left && r === expr.right) expr
            else Expression.Comparison(left = l, op = expr.op, right = r)
        }
        is Expression.CollectionPredicate -> expr
    }
}
