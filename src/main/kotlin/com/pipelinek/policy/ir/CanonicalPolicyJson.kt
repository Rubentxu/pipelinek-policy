package com.pipelinek.policy.ir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.ParamValue
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.selector.Selector
import com.pipelinek.policy.kernel.value.ValueNode
import java.security.MessageDigest

interface PolicyIrCodec {
    fun encode(document: PolicyIrDocument): ByteArray
    fun decode(bytes: ByteArray): PolicyIrDocument
    fun semanticDigest(document: PolicyIrDocument): String
}

/** Canonical JSON codec with no process-local registry. */
object CanonicalPolicyJson : PolicyIrCodec {
    override fun encode(document: PolicyIrDocument): ByteArray = canonical(document).toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): PolicyIrDocument = try {
        val root = JsonParser(bytes.toString(Charsets.UTF_8)).parse().asObject()
        decodeDocument(root)
    } catch (e: IrRefusal) {
        throw e
    } catch (e: IllegalStateException) {
        throw IrRefusal.CorruptEncoding("invalid canonical IR JSON: ${e.message}", e)
    }

    override fun semanticDigest(document: PolicyIrDocument): String = sha256(encode(document))

    private fun decodeDocument(o: Map<String, J>): PolicyIrDocument {
        val version = o.req("irVersion").asInt()
        if (version != 1) throw IrRefusal.CorruptEncoding("unsupported IR version $version")
        val policies = o.req("policies").asArray().map { p ->
            val po = p.asObject()
            Policy(po.req("id").asString(), po.req("rules").asArray().map { r ->
                val ro = r.asObject()
                Rule(
                    ro.req("id").asString(), ro.req("message").asString(),
                    decodeExpression(ro.req("expression").asObject()),
                    ro["appliesWhen"]?.takeUnless { it == J.Null }?.asObject()?.let(::decodeExpression),
                    ro["code"]?.stringOrNull(), ro["expected"]?.stringOrNull(), ro["actual"]?.stringOrNull(),
                    ro["params"]?.asObject()?.mapValues { ParamValue.of(it.value.primitive()) } ?: emptyMap(),
                )
            })
        }
        val refs = o["sourceRefs"]?.asObject()?.mapValues { (_, v) ->
            val r = v.asObject()
            PolicySourceRef(
                r.req("file").asString(), r.req("startLine").asInt(), r.req("startColumn").asInt(),
                r.req("endLine").asInt(), r.req("endColumn").asInt(), r.req("symbol").asString(),
            )
        } ?: emptyMap()
        return PolicyIrDocument(
            PolicySet(o.req("policySetId").asString(), policies),
            o.req("functions").asArray().map { it.asString() },
            emptyMap(), emptyList(), refs, version, o.req("languageVersion").asString(),
        )
    }

    private fun decodeExpression(o: Map<String, J>): Expression = when (val op = o.req("op").asString()) {
        "literal" -> Expression.Literal(decodeValue(o.req("value").asObject()))
        "fieldRef" -> Expression.FieldRef(
            path(o.req("path").asString()),
            ValueNode.Type.valueOf(o.req("type").asString()),
            o["optional"]?.asBoolean() ?: false,
        )
        "not" -> Expression.Not(decodeExpression(o.req("body").asObject()))
        "reference" -> Expression.Reference(o.req("name").asString())
        "comparison" -> Expression.Comparison(
            decodeExpression(o.req("left").asObject()),
            Expression.Operator.valueOf(o.req("operator").asString()),
            decodeExpression(o.req("right").asObject()),
        )
        "collection" -> Expression.CollectionPredicate(
            Expression.CollectionOp.valueOf(o.req("kind").asString()),
            decodeExpression(o.req("source").asObject()),
            Selector.of(path(o.req("predicate").asString())),
        )
        else -> throw IrRefusal.UnsupportedExpression("unknown opcode $op")
    }

    private fun decodeValue(o: Map<String, J>): ValueNode = when (o.req("type").asString()) {
        "MISSING" -> ValueNode.Missing
        "NULL" -> ValueNode.Null
        "TEXT" -> ValueNode.TextValue(o.req("value").asString())
        "NUMBER" -> {
            val lex = o.req("value").numberString()
            ValueNode.NumberValue(numberOfLexical(lex))
        }
        "BOOLEAN" -> ValueNode.BooleanValue(o.req("value").asBoolean())
        "SEQUENCE" -> ValueNode.SequenceValue(o.req("value").asArray().map { decodeValue(it.asObject()) })
        "MAPPING" -> ValueNode.MappingValue(o.req("value").asObject().mapValues { decodeValue(it.value.asObject()) })
        else -> throw IrRefusal.CorruptEncoding("unknown value type")
    }

    private fun path(s: String) =
        s.split('.').filter(String::isNotEmpty).fold(DocumentPath.ROOT) { p, x -> p.child(x) }

    private fun canonical(d: PolicyIrDocument): String = CanonicalPolicyJsonWriter.write(d)

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

private object CanonicalPolicyJsonWriter {
    fun write(d: PolicyIrDocument): String = buildString {
        append("{\"irVersion\":").append(d.irVersion).append(",\"languageVersion\":").q(d.languageVersion)
        append(",\"policySetId\":").q(d.policySet.id)
        append(",\"functions\":[").append(d.functions.sorted().joinToString(",") { quote(it) }).append(']')
        append(",\"policies\":[")
        d.policySet.policies.sortedBy { it.id }.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"id\":").q(p.id).append(",\"rules\":[")
            p.rules.sortedBy { it.id }.forEachIndexed { j, r ->
                if (j > 0) append(',')
                append("{\"id\":").q(r.id).append(",\"message\":").q(r.message)
                append(",\"expression\":").append(expression(r.expression))
                r.appliesWhen?.let { append(",\"appliesWhen\":").append(expression(it)) }
                append('}')
            }
            append("]}")
        }
        append(']')
        if (d.sourceRefs.isNotEmpty()) {
            append(",\"sourceRefs\":{")
            d.sourceRefs.toSortedMap().entries.forEachIndexed { i, (k, r) ->
                if (i > 0) append(',')
                q(k)
                append(":{\"file\":").q(r.file).append(",\"startLine\":").append(r.startLine)
                append(",\"startColumn\":").append(r.startColumn).append(",\"endLine\":").append(r.endLine)
                append(",\"endColumn\":").append(r.endColumn).append(",\"symbol\":").q(r.symbol).append('}')
            }
            append('}')
        }
        append('}')
    }

    private fun expression(e: Expression): String = when (e) {
        is Expression.Literal -> "{\"op\":\"literal\",\"value\":${value(e.value)}}"
        is Expression.FieldRef -> "{\"op\":\"fieldRef\",\"path\":${quote(e.path.toString())}" +
            ",\"type\":${quote(e.expectedType.name)},\"optional\":${e.optional}}"
        is Expression.Reference -> "{\"op\":\"reference\",\"name\":${quote(e.name)}}"
        is Expression.DatasetRef -> "{\"op\":\"datasetRef\",\"name\":${quote(e.name)}}"
        is Expression.Not -> "{\"op\":\"not\",\"body\":${expression(e.body)}}"
        is Expression.Comparison -> "{\"op\":\"comparison\",\"operator\":${quote(e.op.name)}" +
            ",\"left\":${expression(e.left)},\"right\":${expression(e.right)}}"
        is Expression.CollectionPredicate -> "{\"op\":\"collection\",\"kind\":${quote(e.op.name)}" +
            ",\"source\":${expression(e.source)},\"predicate\":${quote(e.predicate.toString())}}"
    }

    private fun value(v: ValueNode): String = when (v) {
        ValueNode.Missing -> "{\"type\":\"MISSING\",\"value\":null}"
        ValueNode.Null -> "{\"type\":\"NULL\",\"value\":null}"
        is ValueNode.TextValue -> "{\"type\":\"TEXT\",\"value\":${quote(v.text)}}"
        is ValueNode.NumberValue -> "{\"type\":\"NUMBER\",\"value\":${quote(v.number.toString())}}"
        is ValueNode.BooleanValue -> "{\"type\":\"BOOLEAN\",\"value\":${v.boolean}}"
        is ValueNode.SequenceValue ->
            "{\"type\":\"SEQUENCE\",\"value\":[${v.elements.joinToString(",", transform = ::value)}]}"
        is ValueNode.MappingValue -> "{\"type\":\"MAPPING\",\"value\":{" +
            v.entries.toSortedMap().entries.joinToString(",") { quote(it.key) + ":" + value(it.value) } +
            "}}"
    }

    private fun StringBuilder.q(s: String) = append(quote(s))

    private fun quote(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}

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

private fun numberOfLexical(lex: String): kotlin.Number =
    if (lex.contains('.') || lex.contains('e') || lex.contains('E')) {
        lex.toDouble()
    } else {
        lex.toLongOrNull() ?: lex.toDouble()
    }

private fun J.stringOrNull() = if (this == J.Null) null else asString()

private fun J.primitive(): Any = when (this) {
    is J.Str -> v
    is J.Num -> v.toDouble()
    is J.Bool -> v
    else -> throw IrRefusal.CorruptEncoding("primitive expected")
}

private fun Map<String, J>.req(k: String) = this[k] ?: throw IrRefusal.CorruptEncoding("missing $k")

private class JsonParser(private val s: String) {
    var i = 0

    fun parse(): J {
        ws()
        val x = v()
        ws()
        if (i != s.length) error("trailing data")
        return x
    }

    private fun v(): J {
        ws()
        return when (s.getOrNull(i)) {
            '{' -> obj()
            '[' -> arr()
            '"' -> J.Str(str())
            't' -> { lit("true"); J.Bool(true) }
            'f' -> { lit("false"); J.Bool(false) }
            'n' -> { lit("null"); J.Null }
            else -> J.Num(num())
        }
    }

    private fun obj(): J {
        i++; ws()
        val m = linkedMapOf<String, J>()
        if (s.getOrNull(i) == '}') { i++; return J.Obj(m) }
        while (true) {
            ws(); val k = str(); ws(); need(':'); m[k] = v(); ws()
            if (s.getOrNull(i) == '}') { i++; return J.Obj(m) }
            need(',')
        }
    }

    private fun arr(): J {
        i++; ws()
        val a = mutableListOf<J>()
        if (s.getOrNull(i) == ']') { i++; return J.Arr(a) }
        while (true) {
            a += v(); ws()
            if (s.getOrNull(i) == ']') { i++; return J.Arr(a) }
            need(',')
        }
    }

    private fun str(): String {
        need('"')
        val b = StringBuilder()
        while (true) {
            val c = s.getOrNull(i++) ?: error("eof")
            if (c == '"') return b.toString()
            if (c == '\\') {
                val e = s.getOrNull(i++) ?: error("eof")
                b.append(
                    when (e) {
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '"' -> '"'; '\\' -> '\\'
                        else -> e
                    },
                )
            } else {
                b.append(c)
            }
        }
    }

    private fun num(): String {
        val st = i
        while (s.getOrNull(i)?.let { it.isDigit() || it in "+-.eE" } == true) i++
        return s.substring(st, i)
    }

    private fun lit(x: String) {
        if (!s.startsWith(x, i)) error("literal")
        i += x.length
    }

    private fun need(c: Char) {
        ws()
        if (s.getOrNull(i++) != c) error("expected $c")
    }

    private fun ws() {
        while (s.getOrNull(i)?.isWhitespace() == true) i++
    }
}

fun PolicyIrCodec.semanticDigest(document: PolicyIrDocument): String = semanticDigest(document)
