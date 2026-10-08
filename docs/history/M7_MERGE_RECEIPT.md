# M7 Merge Receipt (managed-closure, ADR-0075)

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff`
**Delivery kind:** managed-closure-delivery (bypass de merge a origin/main)
**Commit:** b9b8f431fb0af3cf6f60ccc83bc683f57af87def (rama feat/m7-layers-waivers-shadow-diff)
**Base:** c530c32 (M6 closure addendum, main)

## Justificación del bypass

P0 fixture-license deferral sigue activo: no se publica en remoto ni se
emiten tags. Ruta autorizada ADR-0075: vault release + 3 gates
(vault-receipt-verified, vault-index-current, release-bypass-declared).
Igual que M5 y M6.

## Evidencia

- check BUILD SUCCESSFUL: 219 tests, 0 failures, detekt clean (OBSERVED)
- verify PASS: docs/history/M7_VERIFY_REPORT.md
- git sddk-align ack + sddk-close satisfied para b9b8f43 (OBSERVED)
