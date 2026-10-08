package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import java.security.MessageDigest

/**
 * REQ M10-05 (05c) · On-demand 1 GiB CSV characterization entry point.
 *
 * Run via scripts/m10/csv-1gib.sh (Gradle JavaExec on the test classpath):
 * `CertCsvMain <targetBytes>` prints a receipt (rows, elapsed ms, digest).
 * NOT wired into CI on purpose — the 64 MiB CI budget lives in
 * CsvCharacterizationTest; the 1 GiB run is operator-triggered and its
 * receipt lands in docs/history/M10_CSV_1GIB_RECEIPT.md.
 */
object CertCsvMain {

    private val set = PolicySet(
        "csv-1gib",
        listOf(
            Policy(
                "p",
                listOf(
                    Rule(
                        "region-is-known",
                        "region must be region-0",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("region"),
                                ValueNode.Type.TEXT,
                            ),
                            Expression.Operator.TEXT_EQUALS,
                            Expression.Literal(ValueNode.TextValue("region-0")),
                        ),
                    ),
                ),
            ),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val targetBytes = args.getOrNull(0)?.toLongOrNull() ?: (1L shl 30)
        val t0 = System.currentTimeMillis()
        val bytes = SyntheticCsv.bytes(targetBytes)
        val tGen = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val decoded = CsvResourceDecoder().decode(bytes, DecodeOptions())
        val doc = (decoded as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
        val rows = (doc.root as ValueNode.SequenceValue).elements
        val tDecode = System.currentTimeMillis() - t1

        val t2 = System.currentTimeMillis()
        val md = MessageDigest.getInstance("SHA-256")
        rows.forEach { md.update(Evaluator.evaluate(set, it).digest.toByteArray(Charsets.UTF_8)) }
        val digest = md.digest().joinToString("") { "%02x".format(it) }
        val tEval = System.currentTimeMillis() - t2

        println("cert-csv-receipt/v1")
        println("bytes: ${bytes.size}")
        println("rows: ${rows.size}")
        println("generate_ms: $tGen")
        println("decode_ms: $tDecode")
        println("evaluate_ms: $tEval")
        println("report_stream_sha256: $digest")
    }
}
