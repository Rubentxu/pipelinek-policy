package com.pipelinek.policy.kernel.governance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B0.5 · the precedence is a property of the CORE, not of any adapter.
 *
 * This is the falsification of the "parity by construction" claim: if the
 * precedence were re-implemented in the CLI or in the plugin, this test would
 * still pass while the two diverged. What it actually pins is that the single
 * kernel answers the declared matrix, so an adapter that calls it cannot
 * disagree with the other one.
 */
class EnforcementInterpreterTest {

    private fun tally(
        refusals: Int = 0,
        errors: Int = 0,
        violations: Int = 0,
    ) = GovernanceTally(refusals = refusals, errors = errors, violations = violations)

    @Test
    fun `refusal outranks everything`() {
        assertEquals(GovernanceVerdict.REFUSED, EnforcementInterpreter.verdict(tally(1)))
        assertEquals(GovernanceVerdict.REFUSED, EnforcementInterpreter.verdict(tally(1, 2, 3)))
    }

    @Test
    fun `an evaluation error outranks any violation`() {
        assertEquals(GovernanceVerdict.ERRORED, EnforcementInterpreter.verdict(tally(errors = 1)))
        assertEquals(GovernanceVerdict.ERRORED, EnforcementInterpreter.verdict(tally(errors = 1, violations = 5)))
    }

    @Test
    fun `violation alone is a policy verdict and no state is passed`() {
        assertEquals(GovernanceVerdict.VIOLATED, EnforcementInterpreter.verdict(tally(violations = 1)))
        assertEquals(GovernanceVerdict.PASSED, EnforcementInterpreter.verdict(tally()))
    }

    @Test
    fun `an operational failure denies in both enforcement modes`() {
        for (mode in EnforcementMode.entries) {
            assertTrue(EnforcementInterpreter.denies(GovernanceVerdict.REFUSED, mode))
            assertTrue(EnforcementInterpreter.denies(GovernanceVerdict.ERRORED, mode))
        }
    }

    @Test
    fun `a genuine violation denies only under enforced`() {
        assertTrue(EnforcementInterpreter.denies(GovernanceVerdict.VIOLATED, EnforcementMode.ENFORCED))
        assertFalse(EnforcementInterpreter.denies(GovernanceVerdict.VIOLATED, EnforcementMode.SHADOW))
    }

    @Test
    fun `would deny evidence is only a suppressed violation`() {
        assertTrue(
            EnforcementInterpreter.isWouldDenyEvidence(GovernanceVerdict.VIOLATED, EnforcementMode.SHADOW),
        )
        assertFalse(
            EnforcementInterpreter.isWouldDenyEvidence(GovernanceVerdict.VIOLATED, EnforcementMode.ENFORCED),
        )
        assertFalse(
            EnforcementInterpreter.isWouldDenyEvidence(GovernanceVerdict.ERRORED, EnforcementMode.SHADOW),
            "an evaluation error is not a would-deny policy result",
        )
    }

    @Test
    fun `policy verdicts and operational failures stay distinguishable`() {
        assertTrue(EnforcementInterpreter.isOperationalFailure(GovernanceVerdict.REFUSED))
        assertTrue(EnforcementInterpreter.isOperationalFailure(GovernanceVerdict.ERRORED))
        assertFalse(EnforcementInterpreter.isOperationalFailure(GovernanceVerdict.VIOLATED))
        assertFalse(EnforcementInterpreter.isOperationalFailure(GovernanceVerdict.PASSED))
    }

    @Test
    fun `negative counts are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { GovernanceTally(violations = -1) }
        assertFailsWith<IllegalArgumentException> { GovernanceTally(errors = -1) }
        assertFailsWith<IllegalArgumentException> { GovernanceTally(refusals = -1) }
    }
}
