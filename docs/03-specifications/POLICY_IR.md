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
