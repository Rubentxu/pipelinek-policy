package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"DSL symbolic API" — the canonical DSL builders.
 *
 * Target of reference (per ROADMAP §M3):
 *
 *   policy {
 *       require {
 *           root.field("spec").optionalField("replicas").asNumber().gte(number(3))
 *       }
 *   }
 *
 * All builders are `context(BuilderCtx)`-tagged; the receiver is the path
 * being assembled. Each call returns a data-class `Expression` from the
 * kernel ADT (NO `Function0`/`KFunction`/`KClass` retained — law 4).
 *
 * Type narrowing via `asNumber()`/`asText()`/`asBoolean()` is compile-DSL:
 * it sets the `expectedType` of the resulting `FieldRef` so the evaluator
 * emits `TYPE_MISMATCH` when the leaf has a different type (law 9: no silent
 * coercion).
 */
context(ctx: BuilderCtx)
fun root(): PathExpr = PathExpr(path = ctx.path(), optional = false, expectedType = null)

context(ctx: BuilderCtx)
fun optionalRoot(): PathExpr = PathExpr(path = ctx.path(), optional = true, expectedType = null)

/**
 * A typed-but-shape-open DSL expression. `path` is the composed
 * `DocumentPath`; `optional` flips the kernel selector into optional mode
 * (so Missing is preserved as data instead of triggering
 * MISSING_REQUIRED_VALUE). `expectedType` is set by `asNumber()` et al.
 */
data class PathExpr(
    val path: com.pipelinek.policy.kernel.path.DocumentPath,
    val optional: Boolean,
    val expectedType: ValueNode.Type?,
) {
    context(ctx: BuilderCtx)
    fun field(name: String): PathExpr = copy(path = path.child(name))

    context(ctx: BuilderCtx)
    fun optionalField(name: String): PathExpr = copy(path = path.child(name), optional = true)

    context(ctx: BuilderCtx)
    fun asNumber(): TypedExpr = TypedExpr(
        path = path,
        optional = optional,
        expectedType = ValueNode.Type.NUMBER,
    )

    context(ctx: BuilderCtx)
    fun asText(): TypedExpr = TypedExpr(
        path = path,
        optional = optional,
        expectedType = ValueNode.Type.TEXT,
    )

    context(ctx: BuilderCtx)
    fun asBoolean(): TypedExpr = TypedExpr(
        path = path,
        optional = optional,
        expectedType = ValueNode.Type.BOOLEAN,
    )
}

/** A typed DSL expression. Carries the type tag so the right `operator`
 * picks the correct `Operator` enum constant for the comparison. */
data class TypedExpr(
    val path: com.pipelinek.policy.kernel.path.DocumentPath,
    val optional: Boolean,
    val expectedType: ValueNode.Type,
) {
    private fun fieldRef(): Expression = FieldRef(path = path, expectedType = expectedType)

    // --- Numeric operators (only valid for Number values) ---

    context(ctx: BuilderCtx)
    infix fun eq(rhs: Expression): Expression = Comparison(fieldRef(), Operator.EQ, rhs)

    context(ctx: BuilderCtx)
    infix fun neq(rhs: Expression): Expression = Comparison(fieldRef(), Operator.NEQ, rhs)

    context(ctx: BuilderCtx)
    infix fun gt(rhs: Expression): Expression = Comparison(fieldRef(), Operator.GT, rhs)

    context(ctx: BuilderCtx)
    infix fun gte(rhs: Expression): Expression = Comparison(fieldRef(), Operator.GTE, rhs)

    context(ctx: BuilderCtx)
    infix fun lt(rhs: Expression): Expression = Comparison(fieldRef(), Operator.LT, rhs)

    context(ctx: BuilderCtx)
    infix fun lte(rhs: Expression): Expression = Comparison(fieldRef(), Operator.LTE, rhs)

    // --- Text / Boolean operators (mapped to TEXT_EQUALS/BOOLEAN_EQUALS) ---

    context(ctx: BuilderCtx)
    infix fun eqText(rhs: Expression): Expression = Comparison(fieldRef(), Operator.TEXT_EQUALS, rhs)

    context(ctx: BuilderCtx)
    infix fun eqBool(rhs: Expression): Expression = Comparison(fieldRef(), Operator.BOOLEAN_EQUALS, rhs)
}

/**
 * Literal `Expression.Literal` builders. Each returns a kernel data-class;
 * no coercion happens here.
 */
context(ctx: BuilderCtx)
fun number(n: Number): Expression = Literal(ValueNode.NumberValue(n))

context(ctx: BuilderCtx)
fun text(s: String): Expression = Literal(ValueNode.TextValue(s))

context(ctx: BuilderCtx)
fun boolean(b: Boolean): Expression = Literal(ValueNode.BooleanValue(b))
