package com.pipelinek.policy.kernel.policy

/**
 * Spec REQ §"DSL rule combinators" — typed primitive parameters carried on a
 * `Rule` so DSL builders can substitute them at compile-DSL time.
 *
 * The DSL only resolves `Int`/`Long`/`Double`/`String`/`Boolean` here so that
 * the substitute step stays total and never has to widen to `kotlin.Any` /
 * `Map<String, Any?>` (architectural law 7 — `Map<String, Any?>` MUST NOT
 * leak into public domain contracts).
 *
 * Substitution is compile-DSL: the DSL walks `Rule.expression` once and
 * replaces any `Reference(paramName)` node with the corresponding
 * `Literal(ParamValue)` value. There is no runtime param map on the ADT.
 */
sealed interface ParamValue {

    val raw: kotlin.Any

    data class IntV(val value: Int) : ParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class LongV(val value: Long) : ParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class DoubleV(val value: Double) : ParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class StringV(val value: String) : ParamValue {
        override val raw: kotlin.Any get() = value
    }

    data class BooleanV(val value: Boolean) : ParamValue {
        override val raw: kotlin.Any get() = value
    }

    companion object {
        /** Convenience: a typed value of one of the five supported primitives. */
        fun of(v: kotlin.Any): ParamValue = when (v) {
            is Int -> IntV(v)
            is Long -> LongV(v)
            is Double -> DoubleV(v)
            is String -> StringV(v)
            is Boolean -> BooleanV(v)
            else -> throw IllegalArgumentException(
                "ParamValue supports only Int/Long/Double/String/Boolean; got ${v?.javaClass?.simpleName}"
            )
        }
    }
}
