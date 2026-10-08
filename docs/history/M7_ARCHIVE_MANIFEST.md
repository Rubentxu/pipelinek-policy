# M7 Archive Manifest

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` (A-full)
**Status al archivar:** BLOCKED → managed-closure-delivery (ADR-0075)
**Commit:** b9b8f431fb0af3cf6f60ccc83bc683f57af87def (feat/m7-layers-waivers-shadow-diff)

## Artefactos del ciclo

| Artefacto | Path | Estado |
|---|---|---|
| exploration-report | docs/history/M7_EXPLORATION.md | OBSERVED |
| proposal | docs/history/M7_PROPOSAL.md | OBSERVED |
| specification | docs/history/M7_SPECIFICATION.md | OBSERVED (7 REQ, 24 escenarios) |
| design | docs/history/M7_DESIGN.md | OBSERVED |
| implementation-plan | docs/history/M7_PLAN.md | OBSERVED (6 WU) |
| implementation-receipt | docs/history/M7_IMPLEMENTATION_RECEIPT.md | OBSERVED |
| verification-report | docs/history/M7_VERIFY_REPORT.md | OBSERVED (PASS) |
| debt-report | docs/history/M7_DEBT_REPORT.md | OBSERVED (D-M7-1/2 P3) |
| merge-receipt | docs/history/M7_MERGE_RECEIPT.md | OBSERVED (bypass ADR-0075) |
| release-failure-evidence | docs/history/M7_RELEASE_FAILURE_EVIDENCE.md | OBSERVED |
| vault-receipt | cycle-artifacts/vault-receipt.json | OBSERVED (sha256 6ada0fb9…) |
| archive-manifest | docs/history/M7_ARCHIVE_MANIFEST.md | este documento |

## Vault receipt

```json
{
  "receipt_id": "de323e76457632bcfda76ddf3199a768eeabc6d1a4a5ed0e19fdd112c8903e44",
  "gate": "archive.vault.complete",
  "transition": "archive.vault.complete",
  "cycle_id": "p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff",
  "delivery_kind": "managed-closure-delivery",
  "content_hash": "b9b8f431fb0af3cf6f60ccc83bc683f57af87def",
  "timestamp": "2026-10-08T17:56:05.761149239Z",
  "signature": "15c731c7717de4ce60fddc60d5d147913ea76f80c994a39747ff877b37ada2db"
}
```

## Gates del cierre

- implementation-complete: passed (219 tests, 0 failures)
- tests-pass / policy-compliant / debt-severity-assigned / debt-priority-assigned: passed
- no-pending-effects: passed (pre-block)
- block-condition-met: passed (P0 fixture-license deferral)
- release-recovery-authorized: passed
- vault-receipt-verified / vault-index-current / release-bypass-declared: passed

## Deuda registrada

- INC-005 (P2, DEFERRED): mutation tests M6 backfill
- D-M7-1 (P3): transitor severity para categorías diff ENFORCEMENT_*/SEVERITY_CHANGED
- D-M7-2 (P3): registry de autoridades para supersession
