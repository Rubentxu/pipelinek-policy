package com.pipelinek.policy.ir

import java.math.BigDecimal
import java.math.BigInteger

internal fun numberOfLexical(lex: String, kind: String?): Number =
    if (kind == null) untypedNumber(lex) else typedNumber(lex, kind)

private fun untypedNumber(lexical: String): Number =
    if (lexical.contains('.') || lexical.contains('e') || lexical.contains('E')) {
        finiteDouble(lexical)
    } else {
        lexical.toLongOrNull() ?: BigInteger(lexical)
    }

private fun typedNumber(lex: String, kind: String): Number = when (kind) {
    "BYTE" -> lex.toByte()
    "SHORT" -> lex.toShort()
    "INT" -> lex.toInt()
    "LONG" -> lex.toLong()
    "BIG_INTEGER" -> BigInteger(lex)
    "BIG_DECIMAL" -> BigDecimal(lex)
    "FLOAT" -> lex.toFloat().also { if (!it.isFinite()) throw IrRefusal.CorruptEncoding("non-finite Float") }
    "DOUBLE" -> finiteDouble(lex)
    else -> throw IrRefusal.CorruptEncoding("unknown numeric carrier")
}

internal fun finiteDouble(lex: String): Double = lex.toDouble().also {
    if (!it.isFinite()) throw IrRefusal.CorruptEncoding("non-finite Double")
}
