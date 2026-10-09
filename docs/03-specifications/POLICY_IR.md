# PolicyIR — contrato ejecutable canónico

## 1. Propósito

`PolicyIR` desacopla:

- Kotlin source;
- compiler plugin;
- evaluator;
- bundle;
- futuras implementaciones del host.

Debe ser estable, versionado, deterministicamente serializable y libre de código ejecutable arbitrario.

## 2. Envelope

```text
PolicyIrDocument
├── irVersion
├── languageVersion
├── policySetId
├── policySetVersion
├── functions[]
├── parameters[]
├── policies[]
├── inferredShapeConstraints
└── metadata
```

## 3. Rule

```text
RuleIR
├── RuleId
├── applicability : BoolExprIR?
├── assertion     : BoolExprIR
├── assertionMode : REQUIRE | FORBID
├── targetSelector?
├── violationTemplate
└── staticCost
```

## 4. Expression ADT

Mínimo v1:

```text
ExprIR
├── Constant
├── SelectField
├── SelectIndex
├── NarrowType
├── Eq / Neq
├── Gt / Gte / Lt / Lte
├── And / Or / Not
├── Exists / Missing / IsNull
├── TextMatches / StartsWith / EndsWith / Contains
├── All / Any / None
├── Count
├── Entries / Values
├── FunctionCall
└── ParameterRef
```

Unknown opcode = hard refusal.

## 5. Types

```text
ValueType
├── UNKNOWN
├── OBJECT
├── ARRAY(element?)
├── TEXT
├── INTEGER
├── DECIMAL
├── BOOLEAN
├── NULL
└── DOMAIN(function-defined nominal type)
```

## 6. Missing semantics

No incrustar missing behavior ad hoc en cada node. El evaluation context del rule define:

```text
ApplicabilityMissingPolicy
AssertionMissingPolicy
```

pero IR debe registrar dónde hay optional traversal.

## 7. Source map de policy

Cada IR node puede referenciar `PolicySourceRef`:

```text
file
line/column span
symbol/rule id
```

No afecta digest semántico si se desea reproducibilidad independiente de checkout path; definir dos digests:

```text
semanticDigest
artifactDigest
```

## 8. Canonical serialization

Requisitos:

- map order canónico;
- numeric format canonical;
- UTF-8;
- no host paths absolutos en semantic section;
- no build timestamp en semantic digest;
- no random ids;
- no reflection class names como semantics.

Formato inicial recomendado: JSON canonical legible para bootstrap + CBOR/protobuf sólo si medida justifica.

## 9. Static cost annotations

Compiler calcula una estimación estructural:

```text
LOCAL
AGGREGATE
GLOBAL
```

y features:

```text
selectorCount
regexCount
traversalCount
requiresDatasetIndex[]
```

No usar esta metadata como autoridad si puede derivarse del IR; validar contra IR al cargar.

## 10. Compatibility

- unknown minor optional metadata -> ignore según version rules;
- unknown opcode -> refuse;
- changed opcode semantics -> major IR version;
- new opcode -> capability negotiation por manifest.

## 11. Algoritmos ejecutables M5/B2 (implementación v1)

Field order canónico del JSON:

```text
irVersion, languageVersion, policySetId, functions[] (sorted), parameters? (sorted),
shapes? (path/authority sorted), policies[] (sorted by id) -> rules[] (sorted by id),
sourceRefs? (sorted keys)
```

El rule preserva `{id, message, expression, appliesWhen?, code?, expected?, actual?, params?, supersession?}`.
Las expresiones `FieldRef` y los selectores se escriben por segmentos estructurales,
no mediante `toString()`. Los selectores incluyen `expectedType` y `optional`.
`DatasetRef` se escribe y se lee como `{op:"datasetRef",name:...}`.

### Números y parámetros

Los números `ValueNode` llevan `numberKind` y su lexema para conservar exactamente
el carrier (`Byte`, `Short`, `Int`, `Long`, `BigInteger`, `BigDecimal`, `Float` o
`Double`). Los valores no finitos y carriers desconocidos se rechazan. Los
parámetros nuevos usan objetos tipados `{kind,value}`. El lector mantiene la
compatibilidad con los parámetros primitivos por regla del lector v1 anterior.

### semanticDigest

SHA-256, hexadecimal minúsculo, del JSON canónico semántico. Se excluyen los
parámetros globales del documento, los `shapes` y `sourceRefs`; los parámetros de
regla y el resto de campos ejecutables permanecen incluidos. El codec ordena mapas,
policies, reglas y funciones de forma determinista.

### artifactDigest

`sha256( canonicalJsonBytes(document) + metadataBytes )`, donde `metadataBytes` es
UTF-8 determinista con claves ordenadas y formato `k=v` separado por LF. Incluye
los campos completos del documento, entre ellos `sourceRefs`, parámetros y shapes.

### Compatibilidad v1

El decoder acepta la representación v1 histórica de `FieldRef.path`, predicate de
selector string y parámetros primitivos por regla. La admisión de bundles PKB1 es
más estricta: el encoder de compatibilidad debe reproducir exactamente los bytes
históricos para validar manifest y digests. Una forma decodificable cuyo writer
histórico dependiera de una representación no reproducible del selector se
rechaza, no se presume compatible. La fixture versionada
`legacy-v1-source-ref.json` fija los bytes del IR v1 y su SHA-256.
`PolicyBundleAdmissionFortressTest` empaqueta esos bytes en runtime y comprueba
la admisión por `BundleVerifier.verifyPacked`. No hay un blob PKB1 completo como
fixture dorada.
Opcodes desconocidos, UTF-8 inválido, claves JSON duplicadas, números inválidos y
versiones IR no soportadas se rechazan.

### Límites de admisión

`IrAdmissionLimits` por defecto limita IR a 16 MiB, profundidad JSON 128, 200.000
nodos, 10.000 reglas y profundidad de selector 256. Los máximos configurables están
acotados por hard ceilings. `BundleAdmissionLimits` limita por defecto el pack a
32 MiB y cada entrada a 16 MiB, con hard ceilings de 128 MiB y 64 MiB.

Verificación: `CanonicalPolicyJsonFortressTest`, `PolicyBundleAdmissionFortressTest`,
`M5UatAcceptanceTest`, `PolicyBundleRemediationTest` y `BundleReproducibilityTest`.
