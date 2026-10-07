---
cycle: p-dd1a1c7a7d448b0c/m0-bootstrap
phase: apply
work_item: 12788213-822a-428f-8470-c9ca58106019
m0_status: INCOMPLETE · BLOCKED-fixture-license
parent_sha: 62dc4a0e34e93101a70393d25868351f7e039b85
recorded: 2026-10-07T17:45Z
sdki_workitem: 12788213-822a-428f-8470-c9ca58106019
---

# M0 Characterization (DOCUMENTED)

Characterization of the legacy `framework_modular` engine from which
the M0 bootstrap was derived. Every claim below is tagged
**DOCUMENTED** and sourced from the blueprint research notes
preserved under `docs/01-research/`. No claim references runtime
execution of legacy fixtures (which are blocked from import by
license + privacy/PII review on `personal.csv`).

## Where the legacy fixtures live [DOCUMENTED]

The legacy fixtures referenced in the M0 BLOCKED-fixture-license
state are NOT inside this repository and NOT inside the blueprint
directory. They live at:

```
/var/mnt/DiscoChino2-fast/Proyectos/giss/framework_modular/
  modules/policies/build/resources/test/policies/
    inputs/
      kubernetes-deployment.yaml
      kubernetes-deployment.json
      personal.csv            ← potential PII; content not classified
      test-drawio.drawio
      PANDORA_Actualización de DDA.drawio
    rules/
      policies_kubernetes.yaml
      policies_kubernetes_error.yaml
      policies_kubernetes_preconditions.yaml
      policies_kubernetes_preconditions_error.yaml
      policies_project.yaml
      policies_drawio.yaml
      policies_drawio_invalid.yaml
      policies_drawio_relaxed.yaml
```

The blueprint directory `pipelinek-policy-blueprint/` (preserved at
workspace root, gitignored) contains ONLY markdown research notes,
ROADMAP, README, and AGENTS.template. It does NOT contain
`build/resources/test/policies/` and it does NOT contain
`personal.csv`. All claims in this document are sourced from the
markdown research, not from runtime execution of the legacy fixtures.

## Legacy system identity [DOCUMENTED]

- Origin: in-house `framework_modular` engine developed prior to this
  project. Source: `docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md`
  §1 (Architecture Overview).
- Language: Kotlin (JVM). Source:
  `docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md` §1.
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
| ADRs 0001..0009 from the blueprint (initial accepted status) | Legacy runtime fixtures under `/var/mnt/.../framework_modular/modules/policies/build/resources/test/policies/` |
| ROADMAP.md milestone sequence (M0..M10) | `personal.csv` content and any path under `build/resources/test/policies/` |
| Architectural narrative (`ARCHITECTURE.md`, `FUNCTIONAL_CORE.md`, etc.) | Compiled bytecode from the legacy `framework_modular` host |

## Privacy / PII posture [DOCUMENTED + INFERRED]

- OBSERVED: the legacy fixture `personal.csv` exists at
  `/var/mnt/.../framework_modular/modules/policies/build/resources/test/policies/inputs/personal.csv`
  (sha256 `3d493f9433816504c7e20f120ff6d29b3e31044bea25885d041486a677dca7cf`,
  407 bytes — see explore-report.md evidence).
- UNVERIFIED: the actual content of `personal.csv` was not opened or
  classified during this M0 cycle (read-only access via filesystem
  listing only). Whether the file holds PII / GDPR-relevant data is
  therefore an open question for the M0 BLOCKED-fixture-license
  ticket.
- The decision to gate M0 close on a license+PII review is therefore
  based on (a) the absence of any `LICENSE` file in the legacy host
  repo and (b) the existence of a CSV file whose name suggests a
  personal/contact register. The classification of its contents is a
  separate, downstream ticket.

## Reproduction pointers

- Blueprint research files: `docs/01-research/`
  (`FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md`,
  `MARKET_AND_KOTLIN_RESEARCH.md`).
- M0 acceptance evidence (commands + receipt + this characterization):
  `docs/history/M0_BOOTSTRAP_RECEIPT.md` and this file.
- Negative probe evidence (config-time refused `com.pipelinek`
  coordinate + reserved-module-dir):
  `/home/rubentxu/.local/share/sddk/evidence/p-dd1a1c7a7d448b0c/m0-bootstrap/probes/`.