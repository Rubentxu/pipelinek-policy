# Architecture Acceptance Tests / Fitness Functions

## AAT-001 — Domain purity

`policy-domain` y `policy-evaluator` no dependen de:

- Jackson;
- SnakeYAML;
- CSV libs;
- PipelineK;
- Kotlin compiler APIs.

## AAT-002 — Parser isolation

Parsers concretos dependen de `decoder-api/resource-domain`, nunca al revés.

## AAT-003 — No arbitrary policy bytecode

Bundle evaluator no usa `ClassLoader`/reflection para cargar una implementation de policy.

## AAT-004 — FIR is sugar

Compiler plugin tests prueban digest parity con explicit API.

## AAT-005 — No hidden I/O

Static scan/architecture test prohíbe packages de I/O/network/process en evaluator core.

## AAT-006 — No mutable global registry

Registries deben construirse/freeze; prohibir singleton mutable de functions/decoders.

## AAT-007 — Cursor/format independence (future CLI)

Machine output renderer no entra en evaluation call graph.

## AAT-008 — PipelineK no-core-change

`pipeline-kotlin` semantic core no contiene `policy.check` ni package del plugin.

## AAT-009 — Cedar separation

No `AuthorizationDecision`/credential admission semantics dentro de generic compliance evaluator. Cualquier integración futura debe ser adapter explícito.

## AAT-010 — `Any?` containment

`Any?` permitido sólo en host adapters; no en public policy domain contracts.

## AAT-011 — SourceMap separation

`ValueNode` no contiene line/column/source URI.

## AAT-012 — No silent coercion

Búsqueda estática y behavioral fitness aseguran que parsers/evaluator no convierten tipos mediante `toString`/`parse` sin opcode explícito.

## AAT-013 — Roadmap authority

No segundo roadmap activo fuera de root `ROADMAP.md`; documentos de histórico no se interpretan como estado actual.
