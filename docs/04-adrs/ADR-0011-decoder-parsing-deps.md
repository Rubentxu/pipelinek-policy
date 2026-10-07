# ADR-0011 — Parser policy for the M2 Decoder SPI

- Status: ACCEPTED (M2)
- Cycle: p-dd1a1c7a7d448b0c/m2-decoder-spi
- Date: 2026-10-07
- Deciders: orchestrator (sddk-design) on behalf of pipelinek-policy
- Source spec: cycle-artifacts/.../m2-decoder-spi/spec.md §ADDED REQ 1 (Decoder SPI contract) + §MODIFIED REQ 5 (Architecture Fitness Guard); ADR-0006 (decoder surface).

## Context

M1 closed with a hard architectural law 6: format-specific libraries MUST NOT be dependencies of `policy-domain`/evaluator. Concretely, the production allowlist of the project is exactly `org.jetbrains.kotlin:kotlin-stdlib`, enforced before network resolution by the `architectureFitnessGuard` task in `build.gradle.kts`. M2 needs real parsers (JSON, YAML, CSV) without breaking this invariant.

The temptation is to relax law 6 or to find a single library that does it all (e.g. kotlinx-serialization). Both options fail:

- Relaxing law 6 introduces format-specific transitive dependencies into the core reactor. The evaluator stays pure per law 5, but the parser footprint ends up in the consumer's classpath on every policy check, even when only one format is used.
- A unified serialization library either brings reflection (forbidden by law 5), or `kotlinx-serialization` reified generics that complicate the pure value kernel's type tags. Neither preserves the audit posture that lets the kernel be reasoned about in isolation.

The honest path is to keep the core pure and let parsers live in opt-in modules.

## Decision

### D11.1 — Two production buckets

The 2024–2026 production classpath is split into two allowlists maintained by the extended `architectureFitnessGuard`:

1. **`core`** allowlist — exactly `org.jetbrains.kotlin:kotlin-stdlib`. No exceptions. This bucket covers `:app` (the existing root module) and the new `com.pipelinek.policy.decoder` SPI package.
2. **`parsers`** allowlist — per-submodule list. Each submodule of the form `policy-decoders-{json,yaml,csv,map}` declares its own bounded set of coordinates. A core module that declares a parser coordinate fails the guard before any network resolution. A parser module that declares a coordinate outside its declared allowlist fails the guard for the same reason.

The dual-allowlist extension is gated by ADR-0011 and recorded in `MODIFIED REQ §Architecture Fitness Guard`. The smoke-check scenario "no parser submodule declared → guard passes with core allowlist alone" is the regression test that prevents accidental widening of the `core` bucket.

### D11.2 — Per-format library choices

| Format | Library | Coordinate | Why this library and not others |
|---|---|---|---|
| JSON | Jackson streaming (`com.fasterxml.jackson.core:jackson-core`) | `com.fasterxml.jackson.core:jackson-core:2.18.x` | Token-level access for source spans + duplicate-key detection by `currentName()`. No `jackson-databind` ⇒ no JVM reflection; SPI stays pure per law 5. Stable 2.x line, available in the local Gradle cache. |
| YAML | SnakeYAML engine | `org.yaml:snakeyaml-engine:2.x` | Maintained fork that exposes the YAML event API (anchors/aliases) needed for the alias cap and source-span tracking. Mature YAML 1.2 coverage. |
| CSV | none | (hand-rolled) | RFC 4180 grammar fits in a streaming tokenizer + quote-state machine (~80 LOC). Keeps the submodule zero-dependency on `commons-csv` / `univocity`. |
| Map adapter | none | (recursive descent) | Reflection forbidden by law 5. Recursive descent over `Map<String, Any?>`, `String`, `Number`, `Boolean`, `List<Any?>`. Anything else → `DecodeRefusal(code=UNSUPPORTED_HOST_VALUE)`. |

Why not `kotlinx-serialization` or a single umbrella artifact: each of those brings in a fixed transitive set regardless of which formats a consumer uses. Per-submodule isolation keeps `gradle check` PASSING when only the core is built.

### D11.3 — Duplicate-key fail-closed (law 9 extension)

JSON and YAML decoders MUST return `DecodeResult.Refused(code=DUPLICATE_KEY, anchor)` for any repeated map key at any depth. Silent overwrites are forbidden by the same reasoning as law 9 (no silent coercion): the author chose to write a key twice; the decoder must surface that decision rather than discard it. Mutation gate item 2 (`refuse → silently keep last`) MUST kill ≥1 test.

### D11.4 — Bounded YAML alias expansion

SnakeYAML engine allows recursive anchors; an adversarial stream could blow up the heap. The decoder caps alias resolution at `DecodeOptions.yamlAliasCap = 100` (configurable). Exceeding the cap returns `DecodeResult.Refused(code=ALIAS_EXPANSION_EXCEEDED, anchor)`. The cap preserves deterministic, bounded resource use of the pure SPI.

### D11.5 — Default CSV mode is `TEXT_ONLY`

`DecodeOptions.csvMode` defaults to `TEXT_ONLY`. `INFER_WITH_SAMPLE(N)` is opt-in and freezes the inferred schema after rows 1..N; any later row violating the frozen schema is refused with `SCHEMA_FROZEN` rather than silently coerced. Mutation gate item 1 (`default TEXT_ONLY → INFER_WITH_SAMPLE(N=1)`) MUST kill ≥1 test; item 3 (anchor drop) MUST kill ≥1 test.

## Consequences

- The pure-SPI package `com.pipelinek.policy.decoder` can be compiled with `kotlinc -d` and the resulting classpath audited by a `kotlin-stdlib`-exact listing. No parser transitive ever crosses into the SPI's runtimeClasspath.
- Consumers opt into formats by adding `policy-decoders-{format}` to their build. The minimal smoke build (no parser submodule declared) still passes `architectureFitnessGuard`.
- The evaluator and the `canonicalDigest` computation are untouched: `ResourceDocument.root: ValueNode` reuses the M1 kernel and `SourceMap` is a sibling property that does not participate in `canonicalDigest`.
- Future parsers (DrawIO, TOML, Protobuf) plug in as additional `policy-decoders-{format}` modules without reopening this ADR. Each new submodule appends its allowlist and its tests.

## Out of scope

- DrawIO adapter (`SourceAnchor.Element` reserved, adapter deferred to post-M2).
- Compiler plugin / FIR (M3/M4).
- PipelineK external plugin (M6, blocked-by-M5 + S6 certified).
- Switching the CSV submodule to `commons-csv` or `univocity`. Revisit only if real corpora force a feature beyond RFC 4180 + the alias cap.

## References

- ADR-0006 — Decoder SPI + SourceMap preservado durante parsing (the surface this ADR governs).
- ADR-0010 — M1 preflight: build/lint posture and re-pinning (production allowlist origin).
- `docs/03-specifications/RESOURCE_DECODER_SPI.md` — the API surface.
- `docs/02-architecture/DATA_AND_SHAPE_MODEL.md` — `ValueNode` and `SourceMap` definitions.
- ROADMAP §M2.A/B/C/Exit — cycle boundary.

## Implementation status (M2 WU-4, 2026-10)

WU-1 (D11.1 dual-bucket decision): closed. The root `build.gradle.kts`
splits the allowlist into `coreAllowedCoords` and `parserAllowedCoords`;
each parser submodule mirrors its bucket in its own `build.gradle.kts`
and fails the gate on any declared dep outside its bucket.

WU-2 (D-02/D-03/D-04, JSON + YAML parser submodules): closed. Decoders
are in `policy-decoders-json/` and `policy-decoders-yaml/`; both
support the four-item mutation gate items 2 (DUPLICATE_KEY) and 4
(canonicalDigest ignores SourceMap).

WU-3 (D-05/D11.1 hand-rolled CSV + Map adapter): closed. Decoders are
in `policy-decoders-csv/` (hand-rolled RFC 4180 tokenizer) and
`policy-decoders-map/` (recursive descent, no reflection). Both
support mutation gate items 1 (default TEXT_ONLY) and 3 (SCHEMA_FROZEN).

WU-4 (spec anchor + ADR cross-links + mutation gate harness): closed.
The conceptual API sketch in `RESOURCE_DECODER_SPI.md §2` now binds
each conceptual name to the concrete Kotlin type. §11 records the
four-item mutation gate.

Smoke test: with `settings.gradle.kts` excluding every
`policy-decoders-*` line, `:app:check` still passes — verified by
the `ArchitectureFitnessGuardDualAllowlistTest` (no-parsers smoke
check).