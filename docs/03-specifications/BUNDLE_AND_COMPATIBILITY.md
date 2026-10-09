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

## Formato de pack PKB1 (implementación v1)

```text
"PKB1" magic (4 bytes)
entry x4, en orden lexicográfico de nombre:
  nameLen  Int32 BE | name UTF-8 | size Int64 BE | payload bytes
```

Entradas requeridas (exactamente 4): `manifest.json`, `metadata.txt`,
`policy-source-map.txt`, `policy.ir.json`.

Admission (fail-closed, `BundleVerifier.verifyPacked`):

1. el pack y cada entry respetan los presupuestos configurados antes de copiar el payload;
2. magic, cuatro nombres, orden, longitudes, UTF-8 y ausencia de trailing bytes se validan;
3. el IR se decodifica con límites de tamaño, profundidad, nodos, reglas y segmentos;
4. source map y metadata deben coincidir byte por byte con su forma canónica;
5. el manifest completo debe coincidir con el recalculado. No basta con encontrar substrings de digest;
6. se comprueban versión IR soportada y funciones requeridas;
7. cualquier dato inválido produce `BundleRefusal`, incluido truncamiento o UTF-8 malformado.

Presupuestos por defecto: 32 MiB por pack, 16 MiB por entrada, 16 MiB por IR,
128 niveles de JSON, 200.000 nodos, 10.000 reglas y 256 segmentos de selector.
Los valores configurables están limitados por hard ceilings de 128 MiB por pack,
64 MiB por entry, 64 MiB por IR, 512 niveles, 1.000.000 nodos, 100.000 reglas
y 4.096 segmentos.

Compatibilidad: la admisión PKB1 v1 se limita a bundles cuyo IR histórico puede
reemitirse byte por byte por el encoder de compatibilidad y cuyo manifest/digest
coincide con esos bytes y metadata canónica. Decodificar una forma histórica no
basta para admitir el bundle. Se leen `FieldRef.path`, selector string y parámetros
primitivos por regla; si el encoder histórico dependía de una representación de
selector no reproducible, el bundle se rechaza en vez de fingir compatibilidad.
La fixture legacy versionada `legacy-v1-source-ref.json` fija los bytes del IR v1
y su SHA-256. `PolicyBundleAdmissionFortressTest` construye con esos bytes un
contenedor PKB1 en runtime y comprueba la admisión por la API pública
`verifyPacked`. No se mantiene un blob PKB1 completo como fixture dorada.

Reproducibilidad: doble build del mismo documento produce bytes idempotentes
(`BundleReproducibilityTest`). Las pruebas B2 también falsifican cambios de manifest
que conservan ambos digest, alteraciones del source map, presupuestos excedidos y
cada prefijo truncado del bundle.
