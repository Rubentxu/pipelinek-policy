# ADR-0013 — Separate base CLI findings from governance enrichment

- Status: ACCEPTED (B3)
- Cycle: p-dd1a1c7a7d448b0c/m8-datasets-streaming
- Date: 2026-10-09
- Decider: B3 orchestrator under the user's autonomous-execution authorization
- Sources: `docs/03-specifications/CLI_AND_AGENT_SURFACE.md` §5; `ROADMAP.md` B3 and B4; B3 WorkItem `fd07312d-8e4d-4cf6-a820-7081d97ca1cd`

## Context

The CLI specification §5 grouped ordinary finding data with `enforcement`, `rollout`, and `waiverStatus`. The active B3 WorkItem requires complete structured findings while explicitly preserving B4-B6 scope. B4 owns policy governance, including waiver isolation, enforcement responsibility, and faithful shadow behavior. The standalone B3 CLI evaluator has no governance execution context from which to derive those values. Emitting guessed states would fabricate policy facts; implementing the governance path here would cross the active WorkItem boundary.

## Decision

Split the standalone B3 finding schema from the governance-aware extension. B3 findings expose the complete identity, resource, source, and evaluation diagnostic data that the existing public APIs actually provide. B4 may add governance fields only when a governance-aware evaluation path supplies their values.

For the B3 base schema:

| Field | Source / representation |
|---|---|
| `violationId` | Stable `ViolationFingerprint`, also retained as `fingerprint` |
| `policyId`, `ruleId` | Evaluated policy and rule |
| `bundleDigest` | Verified manifest `artifactDigest` |
| `subjectRef` | `ResourceDocument.id.value`, also retained as `resourceId` |
| `sourceAnchor` | Physical `SourceAnchor` at the violated logical path; typed JSON object, or `null` when no binding exists |
| `path` | Logical `DocumentPath` rendering |
| `actual`, `expected` | Existing evaluator diagnostic strings; nullable where the evaluator has no value and JSON-escaped as strings |
| `message` | Original `PolicyViolation.message` |
| `remediation` | Existing actionable remediation text |
| `severity` | Severity carried by the finding producer |

Existing `location`, `state`, `resourceId`, and `fingerprint` keys remain for compatibility. JSON and JSONL use one shared finding-object serializer so their field sets cannot drift.

`enforcement`, `rollout`, and `waiverStatus` are not emitted by the standalone B3 CLI. They are absent, not null-filled or inferred from exit codes. The current spec explicitly assigns their governance-aware meaning to B4. This is a transparent contract correction, not a change to evaluator verdicts or enforcement behavior.

## Consequences

- Agents receive stable policy/rule/resource identity, bundle provenance, logical and physical locations, and evaluator diagnostics from B3.
- Missing physical locations and unavailable `actual`/`expected` values remain explicit without fabricated coordinates or values.
- B4 retains ownership of governance semantics and must define/populate the extension from actual runtime context.
- B3 does not claim production certification or close the M8 cycle.
