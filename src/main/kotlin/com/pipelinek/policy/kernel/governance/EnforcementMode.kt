package com.pipelinek.policy.kernel.governance

/**
 * B4-T8 · enforcement mode, owned by the core so the CLI adapter and the
 * plugin adapter can agree on it without one of them depending on the other.
 *
 * It is a PLAIN enum on purpose. Core permits only the Kotlin stdlib
 * (ADR-0011 D11.1), so `@Serializable` is not available here and is not
 * needed: kotlinx.serialization encodes enums by `name`, and the wire
 * certificate in `EnforcementModeWireTest` pins those exact bytes.
 *
 * ENFORCED: a policy violation denies the operation.
 * SHADOW:  the policy computes would-deny evidence without denying anything.
 *          Operational failures (REFUSED, ERRORED) are never shadowed.
 */
enum class EnforcementMode { ENFORCED, SHADOW }
