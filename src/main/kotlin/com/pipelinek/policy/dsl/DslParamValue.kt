package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.policy.ParamValue as KernelParamValue
import com.pipelinek.policy.kernel.value.ValueNode

/**
 * Spec REQ §"DSL rule combinators" — typed primitive parameters carried on the
 * DSL side during `policy { ... }` build-time.
 *
 * This is the public, data-only surface that builders use to substitute params
 * BEFORE handing the `Rule` to the kernel. The DSL performs the substitution
 * in compile-DSL (during `policy { ... }` execution); at the kernel boundary
 * the rule's `params` map is empty (or populated only with metadata the author
 * attached for diagnostics). The kernel never re-resolves a `params` map at
 * runtime — law 7 says `Map<String, Any?>` MUST NOT leak into the ADT.
 *
 * Substituting at the boundary keeps `Rule.expression` a pure data tree
 * (no `Function0`/`KFunction` types — law 4) and makes the DSL `canonicalDigest`
 * bit-identical to a data-built `PolicySet` (spec §"Parity DSL↔ADT").
 */
sealed interface DslParamValue {

    val raw: kotlin.Any

    data class IntV(val value: Int) : DslParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class LongV(val value: Long) : DslParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class DoubleV(val value: Double) : DslParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class StringV(val value: String) : DslParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class BooleanV(val value: Boolean) : DslParamValue {
        override val raw: kotlin.Any get() = value
    }

    companion object {
        /** Convert to the kernel `ParamValue` for metadata propagation. */
        fun toKernel(v: DslParamValue): KernelParamValue = when (v) {
            is IntV -> KernelParamValue.IntV(v.value)
            is LongV -> KernelParamValue.LongV(v.value)
            is DoubleV -> KernelParamValue.DoubleV(v.value)
            is StringV -> KernelParamValue.StringV(v.value)
            is BooleanV -> KernelParamValue.BooleanV(v.value)
        }

        /** Convert to a `ValueNode` for substitution into `Expression.Literal`. */
        fun toValueNode(v: DslParamValue): ValueNode = when (v) {
            is IntV -> ValueNode.NumberValue(v.value)
            is LongV -> ValueNode.NumberValue(v.value.toDouble())
            is DoubleV -> ValueNode.NumberValue(v.value)
            is StringV -> ValueNode.TextValue(v.value)
            is BooleanV -> ValueNode.BooleanValue(v.value)
        }
    }
}
