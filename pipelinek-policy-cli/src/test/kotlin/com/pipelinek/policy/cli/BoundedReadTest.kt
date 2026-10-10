package com.pipelinek.policy.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B4.7 · Bounded file reads at the CLI edge.
 *
 * The kernel cannot enforce a file-size bound: law 5 forbids filesystem I/O in
 * the evaluator core. Only the host knows about files, so the bound belongs
 * here — and every CLI command used a bare `readBytes()`.
 */
class BoundedReadTest {

    @TempDir
    lateinit var dir: File

    private fun fileOf(name: String, size: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(size) { 0x41 }) }

    @Test
    fun `01a a file within budget reads normally`() {
        val file = fileOf("small.json", 128)
        val ok = assertIs<BoundedRead.ReadResult.Ok>(BoundedRead.read(file, budget = 1024))
        assertEquals(128, ok.bytes.size)
    }

    @Test
    fun `01b a file over budget refuses with the declared length and the limit`() {
        val file = fileOf("big.json", 4096)
        val refused = assertIs<BoundedRead.ReadResult.Refused>(BoundedRead.read(file, budget = 1024))
        val tooLarge = assertIs<BoundedRead.ReadRefusal.TooLarge>(refused.refusal)
        assertEquals(4096L, tooLarge.bytes)
        assertEquals(1024L, tooLarge.limit)
    }

    @Test
    fun `01c the refusal happens before allocation, not after`() {
        // The whole point of checking File.length() first: a file far past the
        // budget must be refused WITHOUT being read into memory. A budget of
        // zero makes any non-empty file the cheapest possible probe.
        val file = fileOf("huge.json", 1 shl 20)
        val refused = assertIs<BoundedRead.ReadResult.Refused>(
            BoundedRead.read(file, budget = 0),
        )
        assertIs<BoundedRead.ReadRefusal.TooLarge>(refused.refusal)
    }

    @Test
    fun `01d the boundary is exact, not off by one`() {
        // An off-by-one here is a real bug in either direction: too strict
        // refuses legitimate input, too loose admits one byte past the budget.
        val file = fileOf("exact.json", 100)
        assertIs<BoundedRead.ReadResult.Ok>(BoundedRead.read(file, budget = 100))
        assertIs<BoundedRead.ReadResult.Refused>(BoundedRead.read(file, budget = 99))
    }

    @Test
    fun `01e an empty file reads as zero bytes`() {
        val file = fileOf("empty.json", 0)
        val ok = assertIs<BoundedRead.ReadResult.Ok>(BoundedRead.read(file, budget = 0))
        assertEquals(0, ok.bytes.size)
    }

    @Test
    fun `01f a missing file does not crash the reader`() {
        // The reader is a guard, not a policy about existence. A missing file
        // must surface as the filesystem's own failure rather than being
        // reported as "too large", which would be a lie.
        val missing = File(dir, "nope.json")
        val outcome = runCatching { BoundedRead.read(missing, budget = 1024) }
        assertTrue(
            outcome.isFailure || outcome.getOrNull() is BoundedRead.ReadResult.Ok,
            "a missing file must not be reported as a size refusal",
        )
    }

    @Test
    fun `01g ReadResult Ok compares by CONTENT not by array identity`() {
        // Two reads of the same file are equal. Data classes over ByteArray
        // compare by reference by default, which would make this false and
        // quietly break any test that compares two reads.
        val file = fileOf("same.json", 64)
        val a = BoundedRead.read(file, budget = 1024)
        val b = BoundedRead.read(file, budget = 1024)
        assertEquals(a, b)
    }

    @Test
    fun `01h the read is CAPPED, so growth cannot be materialised past the budget`() {
        // Falsification target: replacing the capped read in `read()` with an
        // uncapped `readBytes()`.
        //
        // Two earlier attempts at this test were green under the mutation,
        // which is why it looks like this. Both drove the cap through a real
        // [File], where `length()` and the stream always agree by the time
        // the read starts — so capped and uncapped were indistinguishable.
        // Asserting on the refusal fails for the same reason: both say the
        // file grew. The only thing that differs is HOW MUCH was allocated.
        //
        // So the stat and the stream are supplied separately: the file claims
        // 8 KiB, the stream carries 1 MiB, and the byte count is the cap.
        val budget = 8 * 1024L
        val payload = ByteArray(1024 * 1024) { it.toByte() }
        val observed = BoundedRead.observeBytesRead(
            declaredLength = budget,
            budget = budget,
            payload = payload,
        )
        assertEquals(
            budget.toInt() + 1,
            observed,
            "the reader must stop one byte past the budget, not drain the stream",
        )
        assertTrue(
            observed < payload.size,
            "the reader must not materialise the payload it refused",
        )
    }

    @Test
    fun `01h2 a payload inside its declared length is read whole`() {
        // The counterpart: when the stream agrees with the stat and fits, the
        // cap must NOT truncate. A cap that always fired would refuse every
        // legal payload and still pass the growth test above.
        val payload = ByteArray(4096) { it.toByte() }
        val observed = BoundedRead.observeBytesRead(
            declaredLength = payload.size.toLong(),
            budget = 8 * 1024L,
            payload = payload,
        )
        assertEquals(payload.size, observed)
    }

    @Test
    fun `01h3 a stream SHORT of its declared length is not a refusal`() {
        // The stat over-promised and the stream under-delivered. That is not
        // the growth guard's business: the payload fits the budget and must be
        // usable. Treating a short read as a refusal would reject truncated
        // files that were never oversized.
        //
        // The declared length must itself be INSIDE the budget, or the stat
        // guard fires first and this would be testing `TooLarge` again. The
        // first run of this test declared 64 KiB against an 8 KiB budget and
        // failed for exactly that reason.
        val payload = ByteArray(4096) { it.toByte() }
        val declared = 8 * 1024L
        val ok = BoundedRead.read(declared, "short", declared) {
            payload.inputStream()
        }
        val result = assertIs<BoundedRead.ReadResult.Ok>(ok)
        assertContentEquals(payload, result.bytes)
    }

    @Test
    fun `01i a file exactly at the budget is accepted`() {
        // Boundary. Off-by-one here would either refuse a legal payload or
        // accept an illegal one, and both are silent until production.
        val exact = dir.resolve("exact.json")
        val size = 8 * 1024
        exact.writeBytes(ByteArray(size) { it.toByte() })
        val ok = assertIs<BoundedRead.ReadResult.Ok>(BoundedRead.read(exact, budget = size.toLong()))
        assertEquals(size, ok.bytes.size)
    }

    @Test
    fun `01j the named budgets track the kernel rather than a local literal`() {
        // If someone raises the kernel's ingress budget, the host must follow
        // in the same commit. A hardcoded constant here would keep the CLI
        // enforcing a limit the plan no longer agreed to.
        val limits = com.pipelinek.policy.kernel.governance.ResourceIngressLimits()
        assertEquals(limits.maxResourceBytes.toLong(), BoundedRead.DEFAULT_RESOURCE_BUDGET)
        assertEquals(limits.maxBundleBytes.toLong(), BoundedRead.DEFAULT_BUNDLE_BUDGET)
    }
}
