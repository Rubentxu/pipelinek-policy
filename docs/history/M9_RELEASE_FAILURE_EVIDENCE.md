# M9 Release Failure Evidence (block-condition-met, ADR-0075)

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx`
**Condición de bloqueo:** P0 fixture-license deferral activo: publicación
remota suspendida; release.complete no puede producir release-receipt de
merge normal. Igual que M5/M6/M7.

**Recuperación autorizada (ADR-0075):** BLOCKED → managed-closure-delivery
→ `sddk release vault` → gates vault-receipt-verified, vault-index-current,
release-bypass-declared → archive.vault.complete.
