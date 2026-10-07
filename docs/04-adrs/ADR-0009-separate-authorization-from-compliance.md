# ADR-0009 — Separar compliance arbitrario de autorización Cedar

**Status:** ACCEPTED

## Decisión

`pipelinek-policy` responde preguntas sobre facts/resources arbitrarios.

Ejemplos:

- deployment cumple baseline;
- SBOM tiene licencia;
- coverage supera umbral;
- artifact contiene provenance.

Cedar/guardrails responde autorización de efectos:

- este principal puede usar credencial;
- este run puede desplegar a prod;
- este actor puede usar capability de red.

## Motivo

Evitar dos engines respondiendo a la misma decisión de seguridad con semánticas incompatibles.
