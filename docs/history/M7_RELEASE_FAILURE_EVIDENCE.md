# M7 Release Failure Evidence (block-condition-met, ADR-0075)

**Cycle:** `p-ddk.../m7-layers-waivers-shadow-diff` (p-dd1a1c7a7d448b0c)
**Condición de bloqueo:** P0 fixture-license deferral activo en el proyecto:
la publicación en remoto (origin) está suspendida, por lo que la transición
release.complete no puede producir un release-receipt de merge normal.

**Evidencia (OBSERVED):**
- `sddk release vault --cycle <C>` responde: "is not BLOCKED
  (status=ReleasePending); archive.vault.complete is only available for
  BLOCKED cycles" — la ruta managed-closure exige BLOCKED primero.
- Último release convencional del proyecto fue suspendido por el mismo
  deferral (M5, M6 idem, vault receipts previos).

**Recuperación autorizada (ADR-0075):** BLOCKED → managed-closure-delivery
→ `sddk release vault` → gates vault-receipt-verified, vault-index-current,
release-bypass-declared → archive.vault.complete.
