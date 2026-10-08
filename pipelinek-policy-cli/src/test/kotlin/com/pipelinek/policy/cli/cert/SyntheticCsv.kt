package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.kernel.value.ValueNode

/**
 * REQ M10-05 · Synthetic CSV generator (design D4).
 *
 * Streaming generator (Sequence<String>): rows carry a temperature column;
 * `temp > 80` violates the canonical rule. The SAME generator powers the
 * 64 MiB CI characterization and the 1 GiB on-demand harness (scripts/m10),
 * so both exercise identical code paths.
 */
object SyntheticCsv {

    const val HEADER = "id,name,region,temp,value,notes"

    /** Deterministic row: temp cycles 50..99 ⇒ predictable violations. */
    fun row(i: Long): String {
        val temp = 50 + (i % 50)
        return "r$i,service-$i,region-${i % 8},$temp,${i * 7 % 1000},synthetic row $i for characterization"
    }

    /** Streaming rows until [targetBytes] (approx, header included). */
    fun rows(targetBytes: Long): Sequence<String> = sequence {
        yield(HEADER)
        var bytes = HEADER.length + 1L
        var i = 0L
        while (bytes < targetBytes) {
            val r = row(i)
            yield(r)
            bytes += r.length + 1
            i++
        }
    }

    fun bytes(targetBytes: Long): ByteArray =
        rows(targetBytes).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

    /** Rows violating the canonical rule (temp > 80), per the generator. */
    fun expectedViolations(totalRows: Long): Long =
        (0 until totalRows).count { 50 + (it % 50) > 80 }.toLong()

    /** The typed tree of one row (CSV cells are TEXT by contract). */
    fun rowTree(i: Long): ValueNode = ValueNode.MappingValue(
        mapOf(
            "id" to ValueNode.TextValue("r$i"),
            "name" to ValueNode.TextValue("service-$i"),
            "region" to ValueNode.TextValue("region-${i % 8}"),
            "temp" to ValueNode.TextValue((50 + (i % 50)).toString()),
            "value" to ValueNode.TextValue((i * 7 % 1000).toString()),
            "notes" to ValueNode.TextValue("synthetic row $i for characterization"),
        ),
    )
}
