package com.pipelinek.policy.cli

/**
 * M9 · stable exit codes (REQ-M9-06).
 *
 * 0 success (no violations) · 1 violations present ·
 * 2 usage error / refusal / decode error · 3 unexpected internal error.
 */
object ExitCodes {
    const val OK: Int = 0
    const val VIOLATIONS: Int = 1
    const val USAGE: Int = 2
    const val INTERNAL: Int = 3
}
