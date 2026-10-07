# ADR-0007 — Layers monotónicos y waivers gobernados

**Status:** ACCEPTED

## Decisión

Jerarquía `PLATFORM > ORGANIZATION > PROJECT > PIPELINE_LOCAL`.

Lower layer no puede relajar mandatory higher-layer policy sin grant explícito.

Waivers son objetos separados con scope, issuer, reason y expiry.

## Rechazado

- `disablePolicy=true`;
- duplicate id `last wins`;
- pipeline local auto-waiver de platform rules.
