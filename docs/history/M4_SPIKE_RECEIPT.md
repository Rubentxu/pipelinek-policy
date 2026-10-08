# M4.A — Spike property synthesis receipt

> Cycle `p-dd1a1c7a7d448b0c/m4-fir-authoring` (A-full, phase=`release`).
> Evidence branch: `m4-fir-spike`, preserved separately from `main`.
> Candidate SHA: `9b2384d11a49941364b0a9f322918bac81f10637`.

## Verdict

**Controlled FAIL — Option C adopted.** The spike does not claim a real FIR implementation. The fallback remains the authoritative authoring route:

```kotlin
root.anything.whatever.text()
```

with missing-aware AST semantics, operational in M3.

## Gate table

| Gate | Result | Evidence and limitation |
|---|---|---|
| G1 canonical IR parity | PASS | FIR-sugar and explicit ADT digests equal; the mislowering mutation differs. Synthetic oracle only. |
| G2 evaluator parity | PASS | Equal typed `FieldRef` for `anything.whatever`. |
| G3 IDE baseline | PARTIAL | K2 reflection probe passes, but the IntelliJ binary baseline was not exercised offline. |
| G4 incremental compile | PASS | Unrelated class SHA-256 remains stable after source edit. Synthetic offline evidence. |
| G5 `.kts` | FAIL | Cached Kotlin scripting 2.4.10 ABI does not expose `JvmScriptCompiler`. |

## Reproduction

```text
./gradle-jdk21.sh :policy-fir-plugin:test :test --offline --no-daemon
```

Observed result: exit 0. The green test command does not override the per-gate FAIL/PARTIAL outcomes above.

## Evidence references

- Verification report: `cycle-artifacts/p-dd1a1c7a7d448b0c/m4-fir-authoring/verify-report.md`
- Gate contract: `docs/history/M4_GATE_EVIDENCE_CONTRACT.json`
- Gate logs: `docs/history/m4-gate-evidence/gate-{1,2,3,4,5}.*`
- Full candidate branch: `m4-fir-spike`

## Decision

Option C is adopted. No ADR-0012 is created. No real FIR provider or compiler-specific semantic authority is promoted from this spike. Follow-up debt remains tracked for a provisioned IntelliJ baseline, Kotlin scripting ABI compatibility, and a real FIR implementation lane.
