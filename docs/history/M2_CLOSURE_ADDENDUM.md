# M2 CLOSED — Decoder SPI + source-aware resources

- Cycle `p-dd1a1c7a7d448b0c/m2-decoder-spi` (path **A-lite**) CLOSED via
  `archive.vault.complete` (managed closure, ADR-0075) at 2026-10-07T22:17Z,
  event `evt-d39c43ed`.
- Published subject: `main = 721f644f76de3dace486e5d7125868f9ea62b41a` on
  `origin` (`Rubentxu/pipelinek-policy`), verified twice. No tag, no GitHub
  release (fixture-license deferral still active, INC-M2-DSPI-CARRY-001).
- vault-receipt sha256
  `744b1242a11487307769c3e8b44d39e1a5b4f877b2a07c0b1d2d1dedcb69f292`;
  archive-manifest sha256 `e58183af…`; ledger 50 eventos.
- Scope delivered (5 commits, `ce87f4f..721f644`, +2781/-50 LOC, 30 files):
  Decoder SPI (ResourceDecoder, ResourceDocument, NodeId, SourceMap,
  SourceAnchor, decode refusals) con canonical digest; submódulos opt-in
  policy-decoders-{json,yaml,csv,map} (Jackson streaming, SnakeYAML,
  hand-rolled CSV, Map adapter) bajo ADR-0011; core sigue EXACTAMENTE
  kotlin-stdlib (dual architectureFitnessGuard).
- Verificación: 13/13 escenarios spec, 105/105 tests, detekt 0,
  mutation gate 3/3 vivo (csv-text-only, json-dup-key, csv-schema-frozen).
- Debt carried forward:
  - `INC-M2-DSPI-CARRY-001` (P0, heredada): fixture-license/PII deferral —
    tags bloqueados hasta relicense o clearance.
  - `INC-M2-DSPI-DEBT-001` (P1, LOW): 5 findings backlog (2 long-method
    CSV/YAML decode, 3 duplicaciones: coerceNumber, Refusal classes,
    fitness-guard en 5 build.gradle).

## ROADMAP effect

`ROADMAP.md` estado block updates to:

- `M2 = DONE · managed-closure (vault 744b1242)`
- `M3 = NEXT`
