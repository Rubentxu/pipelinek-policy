# Pure Function Catalog

## 1. Motivo

El DSL necesita expresividad de dominio sin abrir escape a Kotlin/JVM arbitrario.

## 2. Contrato

```text
PolicyFunction
├── FunctionId
├── version
├── input types
├── output type
├── totality model
├── deterministic = true
├── max/static cost model
└── evaluator implementation owned/trusted by engine
```

## 3. v1 core

### Structural

```text
exists
missing
isNull
size
count
```

### Text

```text
matches
startsWith
endsWith
contains
isBlank
isNotBlank
lowercaseRootLocale?  # evitar locale host; definir Unicode/root semantics
```

### Numeric

```text
compare
add/subtract sólo si caso real; no convertir DSL en lenguaje general innecesario
```

### Collections

```text
all
any
none
contains
count
```

## 4. Domain candidates

Adoptar sólo con tests/casos reales:

```text
semver
quantity (K8s-like units pero provider-neutral)
duration
ip/cidr
uri
sha256 over provided bytes/text
```

## 5. Partial functions

Parse de SemVer/quantity puede fallar. Resultado no se representa con exception sino con typed parse result integrado en expression semantics.

## 6. Versioning

Cambiar semántica de una function id requiere nueva version/function id o major language version. Bundle declara requisitos.
