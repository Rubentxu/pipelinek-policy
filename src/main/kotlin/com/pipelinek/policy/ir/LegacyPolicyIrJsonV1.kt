package com.pipelinek.policy.ir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode

/** Reproduces the v1 writer present at the B2 baseline for verifiable legacy bundles. */
internal object LegacyPolicyIrJsonV1 {
    fun encode(document: PolicyIrDocument): ByteArray? {
        if (document.policySet.policies.any(::hasUnstableSelector)) return null
        return LegacyPolicyIrJsonV1Writer.write(document).toByteArray(Charsets.UTF_8)
    }

    private fun hasUnstableSelector(policy: Policy): Boolean = policy.rules.any { rule ->
        hasUnstableSelector(rule)
    }

    private fun hasUnstableSelector(rule: Rule): Boolean =
        hasUnstableSelector(rule.expression) || rule.appliesWhen?.let(::hasUnstableSelector) == true

    private fun hasUnstableSelector(expression: Expression): Boolean = when (expression) {
        is Expression.CollectionPredicate -> true
        is Expression.Not -> hasUnstableSelector(expression.body)
        is Expression.Comparison -> hasUnstableSelector(expression.left) || hasUnstableSelector(expression.right)
        else -> false
    }
}

private object LegacyPolicyIrJsonV1Writer {
    fun write(document: PolicyIrDocument): String = buildString {
        append("{\"irVersion\":").append(document.irVersion).append(",\"languageVersion\":")
            .append(quote(document.languageVersion))
        append(",\"policySetId\":").append(quote(document.policySet.id))
        append(",\"functions\":[")
            .append(document.functions.sorted().joinToString(",", transform = ::quote)).append(']')
        append(",\"policies\":[")
        document.policySet.policies.sortedBy { it.id }.forEachIndexed { policyIndex, policy ->
            if (policyIndex > 0) append(',')
            append("{\"id\":").append(quote(policy.id)).append(",\"rules\":[")
            policy.rules.sortedBy { it.id }.forEachIndexed { ruleIndex, rule ->
                if (ruleIndex > 0) append(',')
                append("{\"id\":").append(quote(rule.id)).append(",\"message\":")
                    .append(quote(rule.message)).append(",\"expression\":")
                    .append(expression(rule.expression))
                rule.appliesWhen?.let { append(",\"appliesWhen\":").append(expression(it)) }
                append('}')
            }
            append("]}")
        }
        append(']')
        if (document.sourceRefs.isNotEmpty()) {
            append(",\"sourceRefs\":{")
            document.sourceRefs.toSortedMap().entries.forEachIndexed { index, (key, ref) ->
                if (index > 0) append(',')
                append(quote(key)).append(":{\"file\":").append(quote(ref.file))
                    .append(",\"startLine\":").append(ref.startLine)
                    .append(",\"startColumn\":").append(ref.startColumn)
                    .append(",\"endLine\":").append(ref.endLine)
                    .append(",\"endColumn\":").append(ref.endColumn)
                    .append(",\"symbol\":").append(quote(ref.symbol)).append('}')
            }
            append('}')
        }
        append('}')
    }

    private fun expression(expression: Expression): String = when (expression) {
        is Expression.Literal -> "{\"op\":\"literal\",\"value\":${value(expression.value)}}"
        is Expression.FieldRef -> "{\"op\":\"fieldRef\",\"path\":${quote(expression.path.toString())}" +
            ",\"type\":${quote(expression.expectedType.name)},\"optional\":${expression.optional}}"
        is Expression.Reference -> "{\"op\":\"reference\",\"name\":${quote(expression.name)}}"
        is Expression.DatasetRef -> "{\"op\":\"datasetRef\",\"name\":${quote(expression.name)}}"
        is Expression.Not -> "{\"op\":\"not\",\"body\":${expression(expression.body)}}"
        is Expression.Comparison -> "{\"op\":\"comparison\",\"operator\":${quote(expression.op.name)}" +
            ",\"left\":${expression(expression.left)},\"right\":${expression(expression.right)}}"
        is Expression.CollectionPredicate -> error("legacy selector bytes are not reproducible")
    }

    private fun value(value: ValueNode): String = when (value) {
        ValueNode.Missing -> "{\"type\":\"MISSING\",\"value\":null}"
        ValueNode.Null -> "{\"type\":\"NULL\",\"value\":null}"
        is ValueNode.TextValue -> "{\"type\":\"TEXT\",\"value\":${quote(value.text)}}"
        is ValueNode.NumberValue -> "{\"type\":\"NUMBER\",\"value\":${quote(value.number.toString())}}"
        is ValueNode.BooleanValue -> "{\"type\":\"BOOLEAN\",\"value\":${value.boolean}}"
        is ValueNode.SequenceValue ->
            "{\"type\":\"SEQUENCE\",\"value\":[${value.elements.joinToString(",", transform = ::value)}]}"
        is ValueNode.MappingValue -> "{\"type\":\"MAPPING\",\"value\":{" +
            value.entries.toSortedMap().entries.joinToString(",") { quote(it.key) + ":" + value(it.value) } +
            "}}"
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
