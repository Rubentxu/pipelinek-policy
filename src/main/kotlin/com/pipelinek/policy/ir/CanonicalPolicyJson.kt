package com.pipelinek.policy.ir

import java.security.MessageDigest

interface PolicyIrCodec {
    fun encode(document: PolicyIrDocument): ByteArray
    fun decode(bytes: ByteArray): PolicyIrDocument
    fun semanticDigest(document: PolicyIrDocument): String
}

/** Deterministic bootstrap codec. The canonical payload is also retained for decode in-process. */
object CanonicalPolicyJson : PolicyIrCodec {
    private val documents = mutableMapOf<String, PolicyIrDocument>()
    override fun encode(document: PolicyIrDocument): ByteArray {
        val text = canonical(document)
        val bytes = text.toByteArray(Charsets.UTF_8)
        documents[sha256(bytes)] = document
        return bytes
    }
    override fun decode(bytes: ByteArray): PolicyIrDocument = documents[sha256(bytes)]
        ?: throw IrRefusal.CorruptEncoding("unknown or corrupted canonical IR bytes")
    override fun semanticDigest(document: PolicyIrDocument): String = sha256(canonical(document).toByteArray(Charsets.UTF_8))

    private fun canonical(d: PolicyIrDocument): String = buildString {
        append("{\"irVersion\":").append(d.irVersion)
        append(",\"languageVersion\":").quote(d.languageVersion)
        append(",\"policySetId\":").quote(d.policySet.id)
        append(",\"functions\":[").append(d.functions.sorted().joinToString(",") { quote(it) }).append(']')
        append(",\"policies\":[")
        d.policySet.policies.sortedBy { it.id }.forEachIndexed { pi, p ->
            if (pi > 0) append(','); append("{\"id\":").quote(p.id).append(",\"rules\":[")
            p.rules.sortedBy { it.id }.forEachIndexed { ri, r ->
                if (ri > 0) append(','); append("{\"id\":").quote(r.id).append(",\"message\":").quote(r.message)
                append(",\"expression\":").append(expression(r.expression)).append('}')
            }
            append("]}")
        }
        append("]}")
    }
    private fun StringBuilder.quote(s: String): StringBuilder = append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"')
    private fun quote(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun expression(e: com.pipelinek.policy.kernel.expression.Expression): String = when (e) {
        is com.pipelinek.policy.kernel.expression.Expression.Literal -> "{\"op\":\"literal\",\"type\":\"${e.value.type}\",\"value\":\"${e.value}\"}"
        is com.pipelinek.policy.kernel.expression.Expression.FieldRef -> "{\"op\":\"fieldRef\",\"path\":${quote(e.path.toString())},\"type\":\"${e.expectedType}\"}"
        is com.pipelinek.policy.kernel.expression.Expression.Comparison -> "{\"op\":\"comparison\",\"operator\":\"${e.op}\",\"left\":${expression(e.left)},\"right\":${expression(e.right)}}"
        is com.pipelinek.policy.kernel.expression.Expression.Reference -> "{\"op\":\"reference\",\"name\":${quote(e.name)}}"
        is com.pipelinek.policy.kernel.expression.Expression.CollectionPredicate -> "{\"op\":\"collection\",\"kind\":\"${e.op}\",\"source\":${expression(e.source)}}"
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

fun PolicyIrCodec.semanticDigest(document: PolicyIrDocument): String = semanticDigest(document)

object IrRefusalExtensions

private class CorruptEncoding(message: String) : IrRefusal(message)
