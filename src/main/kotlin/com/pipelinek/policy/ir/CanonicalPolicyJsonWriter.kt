package com.pipelinek.policy.ir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.ParamValue
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.Supersession
import com.pipelinek.policy.kernel.policy.SupersessionAuthority
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import java.math.BigDecimal
import java.math.BigInteger

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20

/** Stable canonical JSON encoding for policy IR documents. */
internal object CanonicalPolicyJsonWriter {
    fun write(document: PolicyIrDocument): String = buildString {
        append("{\"irVersion\":").append(document.irVersion)
            .append(",\"languageVersion\":").append(CanonicalPolicyJsonNodeWriter.quote(document.languageVersion))
        append(",\"policySetId\":").append(CanonicalPolicyJsonNodeWriter.quote(document.policySet.id))
        append(",\"functions\":[")
            .append(document.functions.sorted().joinToString(",", transform = CanonicalPolicyJsonNodeWriter::quote))
            .append(']')
        if (document.parameters.isNotEmpty()) append(CanonicalPolicyJsonDocumentSectionsWriter.parameters(document))
        if (document.shapes.isNotEmpty()) append(CanonicalPolicyJsonDocumentSectionsWriter.shapes(document))
        append(CanonicalPolicyJsonDocumentSectionsWriter.policies(document))
        if (document.sourceRefs.isNotEmpty()) append(CanonicalPolicyJsonDocumentSectionsWriter.sourceRefs(document))
        append('}')
    }
}

private object CanonicalPolicyJsonDocumentSectionsWriter {
    fun parameters(document: PolicyIrDocument): String = buildString {
        append(",\"parameters\":{")
        document.parameters.toSortedMap().entries.forEachIndexed { index, (name, parameter) ->
            if (index > 0) append(',')
            append(CanonicalPolicyJsonNodeWriter.quote(name)).append(':')
                .append(CanonicalPolicyJsonNodeWriter.parameter(parameter))
        }
        append('}')
    }

    fun shapes(document: PolicyIrDocument): String = buildString {
        append(",\"shapes\":[")
        document.shapes.sortedWith(compareBy(ShapeConstraint::path, ShapeConstraint::authority))
            .forEachIndexed { index, shape ->
                if (index > 0) append(',')
                append("{\"path\":").append(CanonicalPolicyJsonNodeWriter.quote(shape.path))
                append(",\"type\":").append(CanonicalPolicyJsonNodeWriter.quote(shape.type.name))
                append(",\"authority\":").append(CanonicalPolicyJsonNodeWriter.quote(shape.authority)).append('}')
            }
        append(']')
    }

    fun policies(document: PolicyIrDocument): String = buildString {
        append(",\"policies\":[")
        document.policySet.policies.sortedBy { it.id }.forEachIndexed { policyIndex, policy ->
            if (policyIndex > 0) append(',')
            append("{\"id\":").append(CanonicalPolicyJsonNodeWriter.quote(policy.id)).append(",\"rules\":[")
            policy.rules.sortedBy { it.id }.forEachIndexed { ruleIndex, rule ->
                if (ruleIndex > 0) append(',')
                append("{\"id\":").append(CanonicalPolicyJsonNodeWriter.quote(rule.id))
                    .append(",\"message\":").append(CanonicalPolicyJsonNodeWriter.quote(rule.message))
                    .append(",\"expression\":").append(CanonicalPolicyJsonNodeWriter.expression(rule.expression))
                rule.appliesWhen?.let {
                    append(",\"appliesWhen\":").append(CanonicalPolicyJsonNodeWriter.expression(it))
                }
                rule.code?.let { append(",\"code\":").append(CanonicalPolicyJsonNodeWriter.quote(it)) }
                rule.expected?.let { append(",\"expected\":").append(CanonicalPolicyJsonNodeWriter.quote(it)) }
                rule.actual?.let { append(",\"actual\":").append(CanonicalPolicyJsonNodeWriter.quote(it)) }
                if (rule.params.isNotEmpty()) append(ruleParameters(rule))
                rule.supersession?.let {
                    append(",\"supersession\":").append(CanonicalPolicyJsonNodeWriter.supersession(it))
                }
                append('}')
            }
            append("]}")
        }
        append(']')
    }

    private fun ruleParameters(rule: Rule): String = buildString {
        append(",\"params\":{")
        rule.params.toSortedMap().entries.forEachIndexed { index, (name, parameter) ->
            if (index > 0) append(',')
            append(CanonicalPolicyJsonNodeWriter.quote(name)).append(':')
                .append(CanonicalPolicyJsonNodeWriter.parameter(parameter))
        }
        append('}')
    }

    fun sourceRefs(document: PolicyIrDocument): String = buildString {
        append(",\"sourceRefs\":{")
        document.sourceRefs.toSortedMap().entries.forEachIndexed { index, (key, ref) ->
            if (index > 0) append(',')
            append(CanonicalPolicyJsonNodeWriter.quote(key))
                .append(":{\"file\":").append(CanonicalPolicyJsonNodeWriter.quote(ref.file))
                .append(",\"startLine\":").append(ref.startLine)
                .append(",\"startColumn\":").append(ref.startColumn)
                .append(",\"endLine\":").append(ref.endLine)
                .append(",\"endColumn\":").append(ref.endColumn)
                .append(",\"symbol\":").append(CanonicalPolicyJsonNodeWriter.quote(ref.symbol)).append('}')
        }
        append('}')
    }
}

private object CanonicalPolicyJsonNodeWriter {
    fun expression(expression: Expression): String = when (expression) {
        is Expression.Literal -> "{\"op\":\"literal\",\"value\":${value(expression.value)}}"
        is Expression.FieldRef -> "{\"op\":\"fieldRef\",\"segments\":${pathSegments(expression.path)}" +
            ",\"type\":${quote(expression.expectedType.name)},\"optional\":${expression.optional}}"
        is Expression.Reference -> "{\"op\":\"reference\",\"name\":${quote(expression.name)}}"
        is Expression.DatasetRef -> "{\"op\":\"datasetRef\",\"name\":${quote(expression.name)}}"
        is Expression.Not -> "{\"op\":\"not\",\"body\":${expression(expression.body)}}"
        is Expression.Comparison -> "{\"op\":\"comparison\",\"operator\":${quote(expression.op.name)}" +
            ",\"left\":${expression(expression.left)},\"right\":${expression(expression.right)}}"
        is Expression.CollectionPredicate -> "{\"op\":\"collection\",\"kind\":${quote(expression.op.name)}" +
            ",\"source\":${expression(expression.source)},\"predicate\":${selector(expression.predicate)}}"
    }

    fun parameter(parameter: ParamValue): String = when (parameter) {
        is ParamValue.IntV -> "{\"kind\":\"INT\",\"value\":${quote(parameter.value.toString())}}"
        is ParamValue.LongV -> "{\"kind\":\"LONG\",\"value\":${quote(parameter.value.toString())}}"
        is ParamValue.DoubleV -> {
            require(parameter.value.isFinite()) { "non-finite Double parameter is not JSON encodable" }
            "{\"kind\":\"DOUBLE\",\"value\":${quote(parameter.value.toString())}}"
        }
        is ParamValue.StringV -> "{\"kind\":\"STRING\",\"value\":${quote(parameter.value)}}"
        is ParamValue.BooleanV -> "{\"kind\":\"BOOLEAN\",\"value\":${parameter.value}}"
    }

    fun supersession(value: Supersession): String = "{\"supersedes\":{" +
        "\"policyId\":${quote(value.supersedes.policyId)},\"ruleId\":${quote(value.supersedes.ruleId)}}" +
        ",\"reason\":${quote(value.reason)},\"authority\":${supersessionAuthority(value.authority)}" +
        ",\"scope\":${quote(value.scope)},\"validity\":${quote(value.validity)}}"

    /**
     * B4-T2: the authority claim on the wire. The claim is DATA: it names who
     * the bundle says authorized it. Writing it grants nothing — the host
     * registry decides at composition time. A claim never admitted before T2
     * decoded, because the field was a bare string.
     */
    fun supersessionAuthority(value: SupersessionAuthority): String = "{" +
        "\"issuer\":${quote(value.issuer)}" +
        ",\"grantedLayers\":[${value.grantedLayers.sortedBy { it.ordinal }.joinToString(",") { quote(it.name) }}]" +
        ",\"grantDigest\":${quote(value.grantDigest)}}"

    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < JSON_CONTROL_CHARACTER_LIMIT) {
                    append("\\u%04x".format(char.code))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private fun selector(selector: Selector): String = "{\"segments\":${pathSegments(selector.canonicalPath)}" +
        ",\"expectedType\":${selector.canonicalExpectedType?.name?.let(::quote) ?: "null"}" +
        ",\"optional\":${!selector.isRequired()}}"

    private fun pathSegments(path: DocumentPath): String =
        path.segments.joinToString(separator = ",", prefix = "[", postfix = "]", transform = ::quote)

    private fun value(node: ValueNode): String = when (node) {
        ValueNode.Missing -> "{\"type\":\"MISSING\",\"value\":null}"
        ValueNode.Null -> "{\"type\":\"NULL\",\"value\":null}"
        is ValueNode.TextValue -> "{\"type\":\"TEXT\",\"value\":${quote(node.text)}}"
        is ValueNode.NumberValue -> numberValue(node.number)
        is ValueNode.BooleanValue -> "{\"type\":\"BOOLEAN\",\"value\":${node.boolean}}"
        is ValueNode.SequenceValue ->
            "{\"type\":\"SEQUENCE\",\"value\":[${node.elements.joinToString(",", transform = ::value)}]}"
        is ValueNode.MappingValue -> "{\"type\":\"MAPPING\",\"value\":{" +
            node.entries.toSortedMap().entries.joinToString(",") { quote(it.key) + ":" + value(it.value) } +
            "}}"
    }

    private fun numberValue(number: Number): String {
        val (kind, lexical) = numericCarrier(number)
        return "{\"type\":\"NUMBER\",\"numberKind\":${quote(kind)},\"value\":${quote(lexical)}}"
    }

    private fun numericCarrier(number: Number): Pair<String, String> = when (number) {
        is Byte -> "BYTE" to number.toString()
        is Short -> "SHORT" to number.toString()
        is Int -> "INT" to number.toString()
        is Long -> "LONG" to number.toString()
        is BigInteger -> "BIG_INTEGER" to number.toString()
        is BigDecimal -> "BIG_DECIMAL" to number.toString()
        is Float -> finiteCarrier("FLOAT", number)
        is Double -> finiteCarrier("DOUBLE", number)
        else -> throw IrRefusal.CorruptEncoding("unsupported numeric carrier ${number::class.simpleName}")
    }

    private fun finiteCarrier(kind: String, number: Number): Pair<String, String> {
        require(number.toDouble().isFinite()) { "non-finite $kind is not JSON encodable" }
        return kind to number.toString()
    }
}
