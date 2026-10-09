package com.pipelinek.policy.ir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.ParamValue
import com.pipelinek.policy.kernel.policy.RuleRef
import com.pipelinek.policy.kernel.policy.Supersession
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
private const val UNICODE_ESCAPE_LENGTH = 4

interface PolicyIrCodec {
    fun encode(document: PolicyIrDocument): ByteArray
    fun decode(bytes: ByteArray): PolicyIrDocument
    fun semanticDigest(document: PolicyIrDocument): String
}

/** Canonical JSON codec with no process-local registry. */
object CanonicalPolicyJson : PolicyIrCodec {
    override fun encode(document: PolicyIrDocument): ByteArray = CanonicalPolicyJsonWriter.write(document)
        .toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): PolicyIrDocument = decode(bytes, IrAdmissionLimits.DEFAULT)

    fun decode(bytes: ByteArray, limits: IrAdmissionLimits): PolicyIrDocument =
        PolicyIrDocumentDecoder.decode(bytes, limits)

    override fun semanticDigest(document: PolicyIrDocument): String = sha256(
        encode(document.copy(parameters = emptyMap(), shapes = emptyList(), sourceRefs = emptyMap())),
    )
}

private object PolicyIrDocumentDecoder {
    fun decode(bytes: ByteArray, limits: IrAdmissionLimits): PolicyIrDocument = try {
        if (bytes.size > limits.maxEncodedBytes) {
            throw IrRefusal.CorruptEncoding("canonical IR exceeds encoded byte budget")
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val root = JsonParser(text, limits.maxJsonDepth, limits.maxJsonNodes).parse().asObject()
        decodeDocument(root, limits)
    } catch (e: IrRefusal) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw IrRefusal.CorruptEncoding("invalid canonical IR JSON: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw IrRefusal.CorruptEncoding("invalid canonical IR JSON: ${e.message}", e)
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw IrRefusal.CorruptEncoding("canonical IR is not valid UTF-8", e)
    }

    private fun decodeDocument(root: Map<String, J>, limits: IrAdmissionLimits): PolicyIrDocument {
        val version = root.req("irVersion").asInt()
        if (version != 1) throw IrRefusal.CorruptEncoding("unsupported IR version $version")
        val encodedPolicies = root.req("policies").asArray()
        val ruleCount = encodedPolicies.sumOf { it.asObject().req("rules").asArray().size }
        if (ruleCount > limits.maxRules) throw IrRefusal.CorruptEncoding("IR exceeds rule budget")
        val policies = encodedPolicies.map { encodedPolicy ->
            val policy = encodedPolicy.asObject()
            Policy(policy.req("id").asString(), policy.req("rules").asArray().map { encodedRule ->
                val rule = encodedRule.asObject()
                Rule(
                    rule.req("id").asString(), rule.req("message").asString(),
                    PolicyIrExpressionDecoder.decode(rule.req("expression").asObject(), limits),
                    rule["appliesWhen"]?.takeUnless { it == J.Null }?.asObject()?.let {
                        PolicyIrExpressionDecoder.decode(it, limits)
                    },
                    rule["code"]?.stringOrNull(), rule["expected"]?.stringOrNull(),
                    rule["actual"]?.stringOrNull(), PolicyIrParameterDecoder.decodeRuleParameters(rule["params"]),
                    rule["supersession"]?.takeUnless { it == J.Null }?.asObject()
                        ?.let(PolicyIrParameterDecoder::decodeSupersession),
                )
            })
        }
        return PolicyIrDocument(
            policySet = PolicySet(root.req("policySetId").asString(), policies),
            functions = root.req("functions").asArray().map { it.asString() },
            parameters = PolicyIrParameterDecoder.decodeParameters(root["parameters"]),
            shapes = decodeShapes(root["shapes"]),
            sourceRefs = decodeSourceRefs(root["sourceRefs"]),
            irVersion = version,
            languageVersion = root.req("languageVersion").asString(),
        )
    }

    private fun decodeShapes(value: J?): List<ShapeConstraint> = value?.asArray()?.map { encoded ->
        val shape = encoded.asObject()
        ShapeConstraint(
            shape.req("path").asString(),
            ValueNode.Type.valueOf(shape.req("type").asString()),
            shape.req("authority").asString(),
        )
    } ?: emptyList()

    private fun decodeSourceRefs(value: J?): Map<String, PolicySourceRef> =
        value?.asObject()?.mapValues { (_, encoded) ->
            val ref = encoded.asObject()
            PolicySourceRef(
                ref.req("file").asString(), ref.req("startLine").asInt(), ref.req("startColumn").asInt(),
                ref.req("endLine").asInt(), ref.req("endColumn").asInt(), ref.req("symbol").asString(),
            )
        } ?: emptyMap()
}

private object PolicyIrExpressionDecoder {
    fun decode(value: Map<String, J>, limits: IrAdmissionLimits): Expression =
        when (val opcode = value.req("op").asString()) {
            "literal" -> Expression.Literal(PolicyIrValueDecoder.decode(value.req("value").asObject()))
            "fieldRef" -> Expression.FieldRef(
                value["segments"]?.let { decodePathSegments(it, limits) }
                    ?: path(value.req("path").asString()).also { checkPathDepth(it, limits) },
                ValueNode.Type.valueOf(value.req("type").asString()),
                value["optional"]?.asBoolean() ?: false,
            )
            "datasetRef" -> Expression.DatasetRef(value.req("name").asString())
            "not" -> Expression.Not(decode(value.req("body").asObject(), limits))
            "reference" -> Expression.Reference(value.req("name").asString())
            "comparison" -> Expression.Comparison(
                decode(value.req("left").asObject(), limits),
                Expression.Operator.valueOf(value.req("operator").asString()),
                decode(value.req("right").asObject(), limits),
            )
            "collection" -> Expression.CollectionPredicate(
                Expression.CollectionOp.valueOf(value.req("kind").asString()),
                decode(value.req("source").asObject(), limits),
                decodeSelector(value.req("predicate"), limits),
            )
            else -> throw IrRefusal.UnsupportedExpression("unknown opcode $opcode")
        }

    private fun decodeSelector(value: J, limits: IrAdmissionLimits): Selector = when (value) {
        is J.Str -> Selector.of(path(value.v).also { checkPathDepth(it, limits) })
        is J.Obj -> {
            val selector = Selector.of(decodePathSegments(value.v.req("segments"), limits))
            val expected = value.v["expectedType"]?.takeUnless { it == J.Null }
                ?.asString()?.let(ValueNode.Type::valueOf)
            val typed = expected?.let(selector::expectingType) ?: selector
            if (value.v["optional"]?.asBoolean() == true) typed.asOptional() else typed
        }
        else -> throw IrRefusal.CorruptEncoding("selector object expected")
    }

    private fun decodePathSegments(value: J, limits: IrAdmissionLimits): DocumentPath {
        val segments = value.asArray()
        if (segments.size > limits.maxSelectorDepth) {
            throw IrRefusal.CorruptEncoding("IR exceeds selector depth budget")
        }
        return segments.fold(DocumentPath.ROOT) { path, segment -> path.child(segment.asString()) }
    }

    private fun checkPathDepth(path: DocumentPath, limits: IrAdmissionLimits) {
        if (path.length > limits.maxSelectorDepth) {
            throw IrRefusal.CorruptEncoding("IR exceeds selector depth budget")
        }
    }

    private fun path(value: String) =
        value.split('.').filter(String::isNotEmpty).fold(DocumentPath.ROOT) { path, segment -> path.child(segment) }
}

private object PolicyIrParameterDecoder {
    fun decodeParameters(value: J?): Map<String, ParamValue> =
        value?.asObject()?.mapValues { (_, encoded) -> decodeParameter(encoded) } ?: emptyMap()

    fun decodeRuleParameters(value: J?): Map<String, ParamValue> = value?.asObject()?.mapValues { (_, encoded) ->
        if (encoded is J.Obj) decodeParameter(encoded) else decodeLegacyParameter(encoded)
    } ?: emptyMap()

    private fun decodeParameter(encoded: J): ParamValue {
        val parameter = encoded.asObject()
        val raw = parameter.req("value")
        return when (parameter.req("kind").asString()) {
            "INT" -> ParamValue.IntV(raw.asString().toInt())
            "LONG" -> ParamValue.LongV(raw.asString().toLong())
            "DOUBLE" -> ParamValue.DoubleV(finiteDouble(raw.asString()))
            "STRING" -> ParamValue.StringV(raw.asString())
            "BOOLEAN" -> ParamValue.BooleanV(raw.asBoolean())
            else -> throw IrRefusal.CorruptEncoding("unknown parameter kind")
        }
    }

    private fun decodeLegacyParameter(value: J): ParamValue = when (value) {
        is J.Str -> ParamValue.StringV(value.v)
        is J.Num -> ParamValue.DoubleV(finiteDouble(value.v))
        is J.Bool -> ParamValue.BooleanV(value.v)
        else -> throw IrRefusal.CorruptEncoding("legacy rule parameter must be primitive")
    }

    fun decodeSupersession(value: Map<String, J>): Supersession {
        val target = value.req("supersedes").asObject()
        return Supersession(
            RuleRef(target.req("policyId").asString(), target.req("ruleId").asString()),
            value.req("reason").asString(),
            value.req("authority").asString(),
            value.req("scope").asString(),
            value.req("validity").asString(),
        )
    }
}

private object PolicyIrValueDecoder {
    fun decode(value: Map<String, J>): ValueNode = when (value.req("type").asString()) {
        "MISSING" -> ValueNode.Missing
        "NULL" -> ValueNode.Null
        "TEXT" -> ValueNode.TextValue(value.req("value").asString())
        "NUMBER" -> {
            val lexical = value.req("value").numberString()
            ValueNode.NumberValue(numberOfLexical(lexical, value["numberKind"]?.asString()))
        }
        "BOOLEAN" -> ValueNode.BooleanValue(value.req("value").asBoolean())
        "SEQUENCE" -> ValueNode.SequenceValue(value.req("value").asArray().map { decode(it.asObject()) })
        "MAPPING" -> ValueNode.MappingValue(
            value.req("value").asObject().mapValues { decode(it.value.asObject()) },
        )
        else -> throw IrRefusal.CorruptEncoding("unknown value type")
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private sealed interface J {
    data class Obj(val v: Map<String, J>) : J
    data class Arr(val v: List<J>) : J
    data class Str(val v: String) : J
    data class Num(val v: String) : J
    data class Bool(val v: Boolean) : J
    data object Null : J
}

private fun J.asObject() = (this as? J.Obj)?.v ?: throw IrRefusal.CorruptEncoding("object expected")
private fun J.asArray() = (this as? J.Arr)?.v ?: throw IrRefusal.CorruptEncoding("array expected")
private fun J.asString() = (this as? J.Str)?.v ?: throw IrRefusal.CorruptEncoding("string expected")

private fun J.asInt(): Int = when (this) {
    is J.Str -> v.toIntOrNull()
    is J.Num -> v.toIntOrNull()
    else -> null
} ?: throw IrRefusal.CorruptEncoding("integer expected")

private fun J.asBoolean() = (this as? J.Bool)?.v ?: throw IrRefusal.CorruptEncoding("boolean expected")

private fun J.numberString(): String = when (this) {
    is J.Str -> v
    is J.Num -> v
    else -> throw IrRefusal.CorruptEncoding("number expected")
}

private fun J.stringOrNull() = if (this == J.Null) null else asString()

private fun Map<String, J>.req(k: String) = this[k] ?: throw IrRefusal.CorruptEncoding("missing $k")

private class JsonParser(
    private val s: String,
    private val maxDepth: Int,
    private val maxNodes: Int,
) {
    var i = 0
    private var nodes = 0

    fun parse(): J {
        ws()
        val x = v(0)
        ws()
        if (i != s.length) error("trailing data")
        return x
    }

    private fun v(depth: Int): J {
        if (depth > maxDepth) throw IrRefusal.CorruptEncoding("JSON nesting exceeds depth budget")
        nodes++
        if (nodes > maxNodes) throw IrRefusal.CorruptEncoding("JSON exceeds node budget")
        ws()
        return when (s.getOrNull(i)) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> J.Str(str())
            't' -> { lit("true"); J.Bool(true) }
            'f' -> { lit("false"); J.Bool(false) }
            'n' -> { lit("null"); J.Null }
            else -> J.Num(num())
        }
    }

    private fun obj(depth: Int): J {
        i++; ws()
        val m = linkedMapOf<String, J>()
        if (s.getOrNull(i) == '}') { i++; return J.Obj(m) }
        while (true) {
            ws(); val k = str()
            if (m.containsKey(k)) throw IrRefusal.CorruptEncoding("duplicate JSON object key")
            ws(); need(':'); m[k] = v(depth + 1); ws()
            if (s.getOrNull(i) == '}') { i++; return J.Obj(m) }
            need(',')
        }
    }

    private fun arr(depth: Int): J {
        i++; ws()
        val a = mutableListOf<J>()
        if (s.getOrNull(i) == ']') { i++; return J.Arr(a) }
        while (true) {
            a += v(depth + 1); ws()
            if (s.getOrNull(i) == ']') { i++; return J.Arr(a) }
            need(',')
        }
    }

    private fun str(): String {
        val decoded = JsonStringGrammar.read(s, i)
        i = decoded.endIndex
        return decoded.value
    }

    private fun num(): String {
        val (lexical, endIndex) = JsonNumberGrammar.read(s, i)
        i = endIndex
        return lexical
    }

    private fun lit(x: String) {
        if (!s.startsWith(x, i)) error("literal")
        i += x.length
    }

    private fun need(c: Char) {
        ws()
        if (s.getOrNull(i) != c) error("expected $c")
        i++
    }

    private fun ws() {
        while (true) {
            when (s.getOrNull(i)) {
                ' ', '\n', '\r', '\t' -> i++
                else -> return
            }
        }
    }
}

private data class DecodedJsonString(val value: String, val endIndex: Int)

private object JsonStringGrammar {
    fun read(input: String, startIndex: Int): DecodedJsonString {
        if (input.getOrNull(startIndex) != '"') error("string expected")
        var index = startIndex + 1
        val value = StringBuilder()
        while (true) {
            val character = input.getOrNull(index++) ?: error("eof")
            if (character == '"') return DecodedJsonString(value.toString(), index)
            if (character == '\\') {
                val escaped = input.getOrNull(index++) ?: error("eof")
                if (escaped == 'u') {
                    val decoded = unicodeEscape(input, index)
                    value.append(decoded.first)
                    index = decoded.second
                } else {
                    value.append(escapedCharacter(escaped))
                }
            } else {
                if (character.code < JSON_CONTROL_CHARACTER_LIMIT) error("unescaped control character")
                value.append(character)
            }
        }
    }

    private fun escapedCharacter(value: Char): Char = when (value) {
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        '"' -> '"'
        '/' -> '/'
        '\\' -> '\\'
        else -> error("invalid escape")
    }

    private fun unicodeEscape(input: String, startIndex: Int): Pair<Char, Int> {
        val endIndex = startIndex + UNICODE_ESCAPE_LENGTH
        if (endIndex > input.length) error("incomplete unicode escape")
        val codePoint = input.substring(startIndex, endIndex).toIntOrNull(16) ?: error("invalid unicode escape")
        return codePoint.toChar() to endIndex
    }
}

private object JsonNumberGrammar {
    fun read(input: String, startIndex: Int): Pair<String, Int> {
        var index = startIndex
        if (input.getOrNull(index) == '-') index++
        val integerStart = index
        index = integerPart(input, index)
        if (input.getOrNull(integerStart) == '0' && isDigitAt(input, index)) error("leading zero")
        index = fractionPart(input, index)
        index = exponentPart(input, index)
        return input.substring(startIndex, index) to index
    }

    private fun integerPart(input: String, startIndex: Int): Int = when (input.getOrNull(startIndex)) {
        '0' -> startIndex + 1
        in '1'..'9' -> consumeDigits(input, startIndex)
        else -> error("invalid number")
    }

    private fun fractionPart(input: String, startIndex: Int): Int {
        if (input.getOrNull(startIndex) != '.') return startIndex
        val digitsStart = startIndex + 1
        if (!isDigitAt(input, digitsStart)) error("invalid fraction")
        return consumeDigits(input, digitsStart)
    }

    private fun exponentPart(input: String, startIndex: Int): Int {
        if (input.getOrNull(startIndex) !in listOf('e', 'E')) return startIndex
        var digitsStart = startIndex + 1
        if (input.getOrNull(digitsStart) == '+' || input.getOrNull(digitsStart) == '-') digitsStart++
        if (!isDigitAt(input, digitsStart)) error("invalid exponent")
        return consumeDigits(input, digitsStart)
    }

    private fun consumeDigits(input: String, startIndex: Int): Int {
        var index = startIndex
        while (isDigitAt(input, index)) index++
        return index
    }

    private fun isDigitAt(input: String, index: Int): Boolean =
        input.getOrNull(index)?.let { it in '0'..'9' } == true
}
