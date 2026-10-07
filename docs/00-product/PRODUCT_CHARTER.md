# Product Charter — pipelinek-policy

## 1. Problema

Los equipos necesitan imponer políticas sobre artefactos y facts heterogéneos: manifiestos de Kubernetes, configuración de aplicaciones, CSV, SBOM, informes de seguridad, metadatos de Git, planes de despliegue, resultados de calidad y estructuras propias.

Las soluciones actuales suelen obligar a una de estas concesiones:

- aprender un lenguaje nuevo de políticas;
- escribir YAML con expresiones embebidas;
- ejecutar código general sin garantías de pureza;
- acoplar las reglas a Kubernetes o a un producto concreto;
- tratar JSON/YAML como casos privilegiados;
- perder ubicación exacta del problema al normalizar;
- mezclar autorización de seguridad con compliance documental;
- impedir que el compilador ayude con tipos y autocompletado.

`pipelinek-policy` resuelve ese espacio con Kotlin como lenguaje de autoría y un runtime universal sobre datos estructurados.

## 2. Propuesta de valor

> Escribe políticas en Kotlin sobre cualquier estructura clave/valor, con navegación natural `root.foo?.bar`, tipos graduales, evaluación funcional pura, source locations, bundles reproducibles y ejecución integrada con PipelineK sin convertir las policies en código JVM libre.

## 3. Usuario objetivo

### P1 — Plataforma / DevEx

Quiere políticas reutilizables para:

- convenciones de repositorio;
- metadatos de servicios;
- configuración de despliegue;
- supply chain;
- SBOM/provenance;
- calidad y arquitectura;
- requisitos de release.

### P2 — Equipo de producto

Quiere reglas locales comprensibles y testeables sin aprender Rego/Cedar/CEL.

### P3 — Agente LLM

Necesita resultados estructurados y accionables:

- `policyId` / `ruleId`;
- recurso;
- path exacto;
- línea/columna/celda/elemento;
- actual/expected;
- remedio;
- severidad/enforcement;
- waiver posible/no posible.

### P4 — PipelineK

Necesita un plugin externo real que pressure-testee el SDK de Steps, events, manifests, codecs y DSL sin rutas privilegiadas.

## 4. Casos de uso prioritarios

1. Validar YAML y JSON equivalentes con la misma policy.
2. Validar CSV fila a fila o como dataset.
3. Aplicar políticas a `Map<String, Any?>` construido en memoria.
4. Supply-chain: SBOM, provenance y metadatos de artifacts.
5. Kubernetes sin acoplar el motor a Kubernetes.
6. Policies sobre facts de análisis de código/arquitectura.
7. Comparar bundle N vs N+1 sobre un corpus histórico.
8. Ejecutar policies en shadow antes de enforcement.
9. Emitir findings precisos para un agente reparador.
10. Usar datasets cruzados con índices explícitos cuando el caso lo requiera.

## 5. Principios de producto

### P-01 — Structured facts first

Si un origen puede convertirse en objeto/array/escalar, puede ser policy subject.

### P-02 — Kotlin authoring, portable runtime

Kotlin es la UX; `PolicyIR` es el contrato ejecutable.

### P-03 — Schemas improve, never gate basic usability

Sin schema se puede navegar cualquier clave. Con schema se obtiene más seguridad.

### P-04 — No hidden effects

Una policy no lee red, ficheros, env vars, reloj ni secretos. Los facts se obtienen antes.

### P-05 — Explainability is a feature

Toda decisión debe poder responder:

- qué regla;
- qué datos leyó;
- qué expresión falló;
- dónde está el dato;
- qué esperaba;
- por qué se aplicó;
- qué bundle/version/digest decidió.

### P-06 — No YAML policy language

YAML/JSON/etc. son datos o schemas. Las policies se escriben en Kotlin.

### P-07 — One evaluator

OPEN, OBSERVED y CLOSED shapes usan el mismo engine/IR.

### P-08 — Shadow before blocking

Nuevas policies pueden entrar en `SHADOW`; enforcement fuerte requiere evidencia.

### P-09 — Lower layers cannot silently weaken higher layers

Platform/org/project/pipeline se componen monotónicamente salvo supersession autorizada y auditable.

### P-10 — Agent-first outputs, human-friendly authoring

El humano escribe Kotlin; máquinas consumen JSONL/JSON estructurado y `explain`.

## 6. Objetivos medibles de v1

- JSON/YAML/CSV/Map adapters certificados.
- Misma policy sobre YAML/JSON semánticamente equivalentes produce mismo verdict y mismo `actual/expected`.
- Navegación `root.foo?.bar` usable sin schema.
- Operadores inválidos producen error de compilación.
- Con schema cerrado, typo de propiedad produce error de compilación.
- `PolicyIR` canónico y digest reproducible.
- Bundle no contiene policy bytecode ejecutable como autoridad.
- Evaluator determinista: mismos inputs + mismo bundle → mismo report digest.
- Source map exacto para JSON/YAML/CSV.
- Plugin PipelineK externo ejecutable sin cambios de core.
- `policy diff` entre bundles sobre corpus.
- waivers con scope/reason/expiry.

## 7. No objetivos de v1

- Autorización de credenciales/deploys en sustitución de Cedar.
- Mutación/generación automática de recursos.
- Motor distribuido de policies.
- Marketplace.
- Red dentro de una policy.
- Interpretar Kotlin general como lenguaje runtime.
- Compatibilidad con Groovy policy strings.
- Reimplementar JSON Schema/CUE/OPA.
