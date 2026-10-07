# Seguridad, pureza y trust model

## 1. Threat model

Asumir que una policy puede ser:

- errónea;
- muy costosa;
- maliciosa;
- producida por un proyecto de menor confianza;
- firmada por una autoridad diferente;
- evaluada sobre datos que contienen información sensible.

## 2. Garantía principal

> El runtime de policy no ejecuta código JVM suministrado por el bundle como autoridad de decisión.

Bundle contiene `PolicyIR`, metadata y assets declarativos.

## 3. No-I/O law

El evaluator no posee capacidades para:

```text
FILESYSTEM
NETWORK
PROCESS
CREDENTIALS
ENV
CLOCK
RANDOM
REFLECTION
THREAD CREATION
```

Si se necesita un dato externo, debe convertirse en fact antes.

## 4. Policy authoring purity

El checker FIR rechaza dentro del DSL:

- `java.io` / `java.nio.file` reads;
- HTTP clients;
- `System.getenv/getProperty`;
- `Instant.now` / current time;
- `Random`;
- reflection;
- thread/coroutine launch arbitrario;
- classloading;
- process execution.

## 5. Pure function registry

Funciones de dominio admitidas son versionadas:

```text
text.matches
semver.parse / compare
quantity.parse / compare
duration.parse / compare
cidr.contains
ip.parse
```

Cada función declara:

- stable id;
- version;
- input/output types;
- total/partial semantics;
- max cost model;
- canonical errors.

## 6. Resource sensitivity

PolicyReport no debe copiar por defecto valores completos potencialmente sensibles.

`actual` debe tener políticas de rendering:

```text
PLAIN
HASHED
REDACTED
OMITTED
```

El caller puede marcar paths sensibles antes de evaluación.

## 7. Bundle admission

Antes de evaluar:

1. parse manifest;
2. verify digest;
3. verify IR version compatibility;
4. validate signatures/trust policy si configurada;
5. validate function requirements;
6. validate limits;
7. build immutable evaluation plan.

Fail closed.

## 8. Limits anti-DoS

Configurables pero acotados:

- max IR nodes;
- max regex size;
- max selector depth;
- max rule count;
- max recursion (ideal: no user recursion v1);
- max aggregate memory;
- max violations retained;
- max value size rendered.

No usar timeouts como semántica principal; preferir cost/budget units deterministas cuando sea viable.

## 9. Trust hierarchy

```text
PLATFORM
  ↓
ORGANIZATION
  ↓
PROJECT
  ↓
PIPELINE_LOCAL
```

Un nivel inferior puede añadir restricciones. Para relajar/supersede una regla superior necesita una capability administrativa externa y queda auditado.

## 10. Waivers

Todo waiver requiere:

- stable id;
- target policy/rule;
- scope;
- reason;
- issuer;
- issuedAt como dato firmado;
- expiry;
- optional ticket/reference;
- signature/trust metadata según deployment.

Una policy local no puede auto-concederse un waiver mandatory de plataforma.
