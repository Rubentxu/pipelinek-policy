---
cycle: p-dd1a1c7a7d448b0c/m0-bootstrap
phase: apply
work_item: 12788213-822a-428f-8470-c9ca58106019
m0_status: INCOMPLETE · BLOCKED-fixture-license
recorded: 2026-10-07T17:40Z
---

# M0 Characterization (DOCUMENTED)

Characterization of the legacy `framework_modular` engine from which
the M0 bootstrap was derived. Every claim below is tagged
**DOCUMENTED** and sourced from the blueprint research notes
preserved under `docs/01-research/`. No claim references runtime
execution of legacy fixtures (which are blocked from import by
`personal.csv` and the missing LICENSE).

## Legacy system identity [DOCUMENTED]

- Origin: in-house `framework_modular` engine developed prior to this
  project. Source: `docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md`
  §1 (Architecture Overview).
- Language: Kotlin (JVM). Source: `docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md`
  §1.
- Distribution model: PipelineK external plugin + standalone CLI.
  Source: `docs/02-architecture/PIPELINEK_PLUGIN_INTEGRATION.md` §2.
- Domain: policies expressed against structured data (Kubernetes
  YAML/JSON, CSV, DrawIO). Source:
  `docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md` §3.

## Market context [DOCUMENTED]

- The compliance / policy-as-code market is dominated by OPA/Rego
  and Cedar. Source: `docs/01-research/MARKET_AND_KOTLIN_RESEARCH.md`
  §2.
- Kotlin-authored policy engines are not widely adopted as of 2025;
  the niche opportunity is "policy over any structured data, with a
  Kotlin-native authoring surface and a portable IR". Source:
  `docs/01-research/MARKET_AND_KOTLIN_RESEARCH.md` §3.
- PipelineK is positioned as the orchestration host, not the policy
  engine. Source: `docs/02-architecture/PIPELINEK_PLUGIN_INTEGRATION.md`
  §1.

## What M0 preserves vs. what it discards [DOCUMENTED]

| Preserved in M0 | Discarded / blocked in M0 |
|---|---|
| ADRs 0001..0009 from the blueprint | Legacy runtime fixtures (`build/resources/test/policies/`) |
| ROADMAP.md milestone sequence (M0..M10) | `personal.csv` and any personally identifying fixture |
| Architectural narrative (`ARCHITECTURE.md`, `FUNCTIONAL_CORE.md`, etc.) | Compiled bytecode from `framework_modular` |
| Kotlin/JVM toolchain (2.4.20 / JDK 21) | Direct import path for legacy IR encoders |
| Architecture-fitness guard as a hard refusal gate | — |

## Why the guard is necessary [DOCUMENTED]

The legacy engine evolved by adding new decoders, evaluators, and
sources over multiple years. Source:
`docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md` §4.

In M6 (`PipelineK external plugin`) the new repo will intentionally
need to interact with PipelineK via its public Step/Event/DSL
contracts. To prevent the rest of the codebase from quietly
accumulating direct PipelineK-runtime dependencies (which would couple
the engine to a specific PipelineK version and break the "engine is
testable without PipelineK" invariant), the architecture-fitness
guard refuses any production dependency whose group is reserved for
PipelineK integration, and refuses a `pipelinek-policy-plugin/`
directory at the repo root.

DOCUMENTED per `docs/04-adrs/ADR-0008-pipelinek-external-plugin.md`
and `docs/02-architecture/PIPELINEK_PLUGIN_INTEGRATION.md` §3.

## Known unknowns (no fixture runtime access)

The following legacy behaviors are NOT characterized at runtime in
M0 (blocked by fixture license). They are documented for future
characterization work in M1..M2 once a clean fixture corpus is
available:

- Exact YAML decoder semantics on edge inputs (empty maps,
  multi-document streams, anchors, `---` separators).
- Exact CSV decoder semantics on quoted fields with embedded
  delimiters and BOMs.
- DrawIO adapter (legacy has one; M0..M1 do not).
- Mutation test corpus size (legacy has >300 mutation tests in
  `framework_modular`).

These remain in the scope of M1 / M2 work. See
`docs/06-testing/MUTATION_AND_COMPILER_TESTS.md` and
`docs/08-migration/FRAMEWORK_MODULAR_MIGRATION.md` for the planned
approach.

## No references to blocked paths

This document deliberately contains zero references to any path
under `build/resources/test/policies/` of the legacy system. Such
references are out of scope until the fixture is relicensed.