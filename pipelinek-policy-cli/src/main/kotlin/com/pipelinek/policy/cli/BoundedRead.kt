package com.pipelinek.policy.cli

import com.pipelinek.policy.kernel.governance.ResourceIngressLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * B4.7 · Bounded file reads for the CLI hosts.
 *
 * Every command used `file.readBytes()`, which allocates whatever the file
 * happens to contain. Pointing the CLI at a 20 GB file — or a symlink to one —
 * turns a check into an out-of-memory kill with no diagnostic.
 *
 * The kernel cannot supply this: architectural law 5 forbids filesystem I/O in
 * the evaluator core, so the bound has to live at the host edge. It is a HOST
 * concern precisely because only the host knows about files.
 *
 * The check is [File.length], which the filesystem answers without reading,
 * so an oversized path is refused before a single byte is allocated. The read
 * itself is then capped at one byte past the budget, so a file that grows
 * between the stat and the read is bounded too, rather than detected only
 * after the growth has already been allocated.
 */
object BoundedRead {

    /** Copy granularity. Small enough that an oversize is refused promptly. */
    private const val DEFAULT_CHUNK = 8 * 1024

    /** The default host budget: the same shipped ingress budget the kernel uses. */
    private val LIMITS = ResourceIngressLimits()

    /**
     * Named budgets, so a call site reads as a policy choice instead of
     * re-deriving a limit inline.
     *
     * These DERIVE from the kernel's shipped limits rather than restating the
     * number: a duplicated literal here would silently drift from the kernel
     * budget and the host would enforce something the plan never agreed to.
     */
    val DEFAULT_RESOURCE_BUDGET: Long = LIMITS.maxResourceBytes.toLong()

    val DEFAULT_BUNDLE_BUDGET: Long = LIMITS.maxBundleBytes.toLong()

    /** Refusal vocabulary, mirroring the kernel's so both surfaces read alike. */
    sealed interface ReadRefusal {
        /** The file's reported length exceeds the budget. Nothing was read. */
        data class TooLarge(val path: String, val bytes: Long, val limit: Long) : ReadRefusal

        /** The file grew past the budget between the stat and the read. */
        data class GrewDuringRead(val path: String, val bytes: Int, val limit: Long) : ReadRefusal
    }

    sealed interface ReadResult {
        data class Ok(val bytes: ByteArray) : ReadResult {
            override fun equals(other: Any?): Boolean =
                other is Ok && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class Refused(val refusal: ReadRefusal) : ReadResult
    }

    /**
     * Read [file] fully, or refuse with a typed reason. Total (law 9): every
     * input yields exactly one result and nothing is thrown for a size decision.
     *
     * [budget] is the caller's choice of limit; hosts pass the one matching the
     * payload kind they are about to decode.
     */
    fun read(file: File, budget: Long = LIMITS.maxBundleBytes.toLong()): ReadResult =
        read(file.length(), file.path, budget) { file.inputStream() }

    /**
     * The decision, with its two inputs supplied independently.
     *
     * Splitting the stat from the stream is what makes the growth guard
     * testable. Against a real [File] the two always agree by the time the
     * read begins, so a capped read and an uncapped `readBytes()` were
     * observed to return identical results on every fixture — the cap could
     * be reverted and the suite stayed green. Suppressing the growth here,
     * where the declared length and the stream are separate arguments, is the
     * only way to pin the behaviour instead of assuming it.
     */
    internal fun read(
        declaredLength: Long,
        path: String,
        budget: Long,
        open: () -> InputStream,
    ): ReadResult {
        if (declaredLength > budget) {
            return ReadResult.Refused(ReadRefusal.TooLarge(path, declaredLength, budget))
        }
        // Read at most one byte past the budget, and stop there.
        //
        // A post-read `bytes.size > budget` check would DETECT an oversized
        // stream but not PREVENT it: an uncapped read allocates whatever the
        // stream yields, so a file that grew between the stat and the read
        // could still demand the whole growth in heap before this function
        // got a say. Capping is what makes the budget a bound rather than a
        // post-mortem.
        val bytes = readCapped(open, budget + 1)
        if (bytes.size > budget) {
            // The stat said this fit; the read says otherwise. Report what the
            // stream actually produced so the diagnostic does not blame a size
            // the file never claimed to be.
            return ReadResult.Refused(ReadRefusal.GrewDuringRead(path, bytes.size, budget))
        }
        return ReadResult.Ok(bytes)
    }

    /**
     * Read at most [cap] bytes off the stream, stopping as soon as the cap is
     * reached so an oversized payload is never fully materialised.
     */
    private fun readCapped(open: () -> InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream(minOf(cap, DEFAULT_CHUNK.toLong()).toInt())
        open().use { stream ->
            val buffer = ByteArray(DEFAULT_CHUNK)
            var remaining = cap
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val n = stream.read(buffer, 0, want)
                if (n < 0) break
                out.write(buffer, 0, n)
                remaining -= n
            }
        }
        return out.toByteArray()
    }

    /**
     * How many bytes the production read path pulls off a stream that
     * outgrew the budget.
     *
     * This exists for the falsification test and nothing else. The refusal a
     * capped and an uncapped read produce is identical — both say the file
     * grew — so the cap is otherwise unobservable, and it could be reverted
     * to `readBytes()` with the whole suite still green.
     *
     * It routes through [read] rather than the chunk helper, because testing
     * the helper while production called something else is the exact shape of
     * a green test that proves nothing. An earlier version did precisely that
     * and survived the revert.
     */
    internal fun observeBytesRead(
        declaredLength: Long,
        budget: Long,
        payload: ByteArray,
    ): Int {
        var consumed = 0
        val outcome = read(declaredLength, "observed", budget) {
            object : InputStream() {
                override fun read(): Int = -1

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (consumed >= payload.size) return -1
                    val n = minOf(len.toLong(), payload.size - consumed.toLong()).toInt()
                    payload.copyInto(b, off, consumed, consumed + n)
                    consumed += n
                    return n
                }
            }
        }
        return when (outcome) {
            is ReadResult.Ok -> outcome.bytes.size
            // The cap decided this, not the payload: report what the reader
            // pulled, which is `budget + 1` when it stopped correctly and the
            // whole payload when it did not.
            is ReadResult.Refused -> consumed
        }
    }

    /**
     * Command-shaped convenience: return the bytes, or report the refusal to
     * [out] with [label] and return null.
     *
     * Every command needs the same three lines at the same site, and getting
     * the message slightly different in sixteen places is how a diagnostic
     * drifts. Returning null rather than an exit code keeps the call sites
     * reading as `?: return ExitCodes.ADMISSION_ERROR`.
     */
    fun readOrReport(
        file: File,
        label: String,
        out: (String) -> Unit,
        budget: Long = LIMITS.maxBundleBytes.toLong(),
    ): ByteArray? = when (val r = read(file, budget)) {
        is ReadResult.Ok -> r.bytes
        is ReadResult.Refused -> {
            val refusal = r.refusal
            when (refusal) {
                is ReadRefusal.TooLarge ->
                    out("$label: file too large (${refusal.bytes} bytes, budget ${refusal.limit})")
                is ReadRefusal.GrewDuringRead ->
                    out("$label: file grew past the budget during read (${refusal.bytes} bytes, budget ${refusal.limit})")
            }
            null
        }
    }
}
