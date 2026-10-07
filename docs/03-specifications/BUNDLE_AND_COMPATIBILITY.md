# Bundle, versionado y compatibilidad

## 1. Objetivo

Distribuir policies como artefactos reproducibles, verificables y no ejecutables arbitrariamente.

Extensión sugerida:

```text
.pkpolicy
```

## 2. Layout conceptual

```text
bundle/
├── manifest.json
├── policy.ir.json            # bootstrap; futuro codec binario opcional
├── shapes/
│   └── ...
├── policy-source-map.json
├── docs/
│   └── ...
├── test-vectors/
│   └── ...
├── provenance.json
├── sbom.spdx.json
└── signatures/
```

## 3. Manifest

Campos mínimos:

```text
bundleId
bundleVersion
policyLanguageVersion
irVersion
engineApiRange
semanticDigest
artifactDigest
policySets[]
requiredFunctions[]
shapeAuthorities[]
createdBy tool version
```

Build timestamp, si existe, no entra en semantic digest.

## 4. Reproducibility

Dos builds con:

- mismo source;
- mismas dependencies lockeadas;
- mismo compiler/toolchain;
- mismos declared inputs;

deben producir mismo semantic digest.

Idealmente artifact bytes también reproducibles; si firma/attestation introduce metadata externa, semantic digest sigue estable.

## 5. No executable policy authority

El bundle no requiere cargar clases de policy del autor para evaluar.

Helpers puros deben compilar a primitives/function ids admitidos, no shipping arbitrary JVM methods.

## 6. Compatibility axes

Separar:

```text
Policy DSL API
Compiler plugin API
Policy IR
Pure function catalog
Bundle format
PipelineK plugin Step contract
```

No usar un único “version”.

## 7. Locking

Deployment puede pinnear:

```text
bundle id
version
digest
```

Version sin digest es convenience, no identidad fuerte.
