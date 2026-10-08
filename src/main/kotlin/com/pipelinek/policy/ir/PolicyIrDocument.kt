package com.pipelinek.policy.ir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.ParamValue
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.value.ValueNode

/** Versioned, source-independent representation of the supported kernel ADT. */
data class PolicyIrDocument(
    val policySet: PolicySet,
    val functions: List<String> = emptyList(),
    val parameters: Map<String, ParamValue> = emptyMap(),
    val shapes: List<ShapeConstraint> = emptyList(),
    val sourceRefs: Map<String, PolicySourceRef> = emptyMap(),
    val irVersion: Int = 1,
    val languageVersion: String = "m3",
) {
    init { require(irVersion == 1) { "Only PolicyIR version 1 is supported" } }
}

data class ShapeConstraint(val path: String, val type: ValueNode.Type, val authority: String)
data class PolicySourceRef(
    val file: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val symbol: String,
)

enum class IrOpcode { LITERAL, FIELD_REF, COMPARISON, REFERENCE, COLLECTION_PREDICATE }

sealed class IrRefusal(message: String, cause: Throwable? = null) : IllegalArgumentException(message) {
    init { cause?.let { initCause(it) } }
    class CorruptEncoding(message: String, cause: Throwable? = null) : IrRefusal(message, cause)
    class UnsupportedExpression(message: String) : IrRefusal(message)
    class ShapeContradiction(message: String) : IrRefusal(message)
}

object PolicyIrLowerer {
    fun lower(
        policySet: PolicySet,
        functions: List<String> = emptyList(),
        shapes: List<ShapeConstraint> = emptyList(),
    ): PolicyIrDocument {
        validatePolicy(policySet)
        validateShapes(policySet, shapes)
        return PolicyIrDocument(
            policySet, functions.sorted(), emptyMap(),
            shapes.sortedWith(compareBy({ it.path }, { it.authority })),
        )
    }

    private fun validatePolicy(set: PolicySet) {
        require(set.id.isNotBlank()) { "policy set id must not be blank" }
        set.policies.flatMap { it.rules }.forEach { rule ->
            validateExpression(rule.expression)
            rule.appliesWhen?.let(::validateExpression)
            rule.params.forEach { (_, value) -> ParamValue.of(value.raw) }
        }
    }

    private fun validateExpression(expression: Expression): Unit {
        when (expression) {
            is Expression.Literal -> validateValue(expression.value)
            is Expression.FieldRef -> Unit
            is Expression.Comparison -> { validateExpression(expression.left); validateExpression(expression.right) }
            is Expression.Reference -> require(expression.name.isNotBlank()) { "empty parameter reference" }
            is Expression.CollectionPredicate -> validateExpression(expression.source)
        }
    }

    private fun validateValue(value: ValueNode): Unit {
        when (value) {
            is ValueNode.SequenceValue -> value.elements.forEach { validateValue(it) }
            is ValueNode.MappingValue -> value.entries.values.forEach { validateValue(it) }
            else -> Unit
        }
    }

    private fun validateShapes(set: PolicySet, shapes: List<ShapeConstraint>) {
        val refs = set.policies.flatMap { it.rules }.flatMap { listOfNotNull(it.expression, it.appliesWhen) }
        shapes.forEach { shape ->
            if (shape.path.isBlank() || shape.authority.isBlank()) {
                throw IrRefusal.ShapeContradiction("invalid shape constraint")
            }
            refs.filterIsInstance<Expression.FieldRef>().filter { it.path.toString() == shape.path }.forEach {
                if (it.expectedType != shape.type) {
                    throw IrRefusal.ShapeContradiction("shape ${shape.path} contradicts ${it.expectedType}")
                }
            }
        }
    }
}
