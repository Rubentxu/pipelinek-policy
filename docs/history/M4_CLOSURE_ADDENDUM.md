# M4 Closure Addendum — 2026-10-08

## M4.A CLOSED AS DOCUMENTED FAIL — Option C

M4.A, spike de síntesis de propiedades FIR, termina como **FAIL controlado y documentado**. No se declara una implementación FIR real ni se bloquea el roadmap completo. Se adopta formalmente la Opción C: `root.anything.whatever.text()` baja al camino explícito con AST aware de `Missing`, ya operativo en M3.

### Gate record

| Gate | Resultado | Motivo |
|---|---|---|
| G1 canonical IR parity | PASS | Paridad sintética del AST canónico, con mutation test negativo. |
| G2 evaluator parity | PASS | Paridad estructural del `FieldRef` tipado. |
| G3 IDE baseline | PARTIAL | La baseline binaria IntelliJ no pudo ejecutarse offline. |
| G4 incremental compile | PASS | Fingerprint incremental sintético estable. |
| G5 `.kts` | FAIL | La ABI Kotlin scripting 2.4.10 cacheada no expone `JvmScriptCompiler`. |

La tabla completa y las rutas reproducibles están en [`M4_SPIKE_RECEIPT.md`](M4_SPIKE_RECEIPT.md). La evidencia de verificación permanece en el directorio de artefactos del ciclo.

### Decision and scope

- Opción C queda adoptada como fallback de authoring.
- El puente Regex queda clasificado como sintético, no como lower FIR real.
- No se crea ADR-0012.
- La rama `m4-fir-spike` no se mergea ni se borra. Se conserva como evidencia consultable y se publica en `origin`.
- M4 queda en ROADMAP como `FAIL documentado · Opción C adoptada · spike branch m4-fir-spike conservada`, no como DONE normal.

### Follow-up debt

- INC M4-FIR-001, P1: implementación FIR real deferred.
- INC M4-FIR-002, P2: separar lanes ABI Kotlin 2.4.10 y 2.4.20.
- INC M4-FIR-003, P2: sustituir el puente Regex sintético por evidencia FIR real.
- INC M4-FIR-004, P3: provisionar y ejecutar una baseline IntelliJ offline para G3.
- INC M4-FIR-005, P3: conservar la rama spike sin merge como evidencia.

### Cycle record

- Cycle: `p-dd1a1c7a7d448b0c/m4-fir-authoring`.
- Path: `A-full`.
- Release policy: managed closure via ADR-0075. Fixture-license deferral remains active, therefore no tags and no GitHub release.
- Main publication and vault/archive receipts are recorded by SDDK in the cycle artifacts and ledger.
