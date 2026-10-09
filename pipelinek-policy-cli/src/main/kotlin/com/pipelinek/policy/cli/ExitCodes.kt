package com.pipelinek.policy.cli

/** Stable CLI exit codes, frozen by ADR-0012 and CLI spec §9. */
object ExitCodes {
    const val OK: Int = 0
    const val USAGE: Int = 1
    const val VIOLATIONS: Int = 2
    const val EVALUATION_ERROR: Int = 3
    const val ADMISSION_ERROR: Int = 4
    const val COMPILER_ERROR: Int = 5
    const val INTERNAL: Int = EVALUATION_ERROR
}
