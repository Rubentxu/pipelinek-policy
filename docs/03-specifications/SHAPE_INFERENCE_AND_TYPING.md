# Shape inference y tipado gradual

## 1. Objetivo

Conseguir máxima UX Kotlin sin imponer un schema previo.

## 2. Fuentes de conocimiento

Prioridad de autoridad:

```text
formal schema / Kotlin model    CLOSED
        ↓
explicit shape declaration      CLOSED/OPEN híbrido
        ↓
observed samples                OBSERVED
        ↓
constraints from policy source  OBSERVED
        ↓
none                            OPEN
```

## 3. Open shape

Un `ObjectExpr<OpenShape>` acepta cualquier property name.

```kotlin
root.foo.bar
```

produce campos simbólicos `foo/bar`, inicialmente `UnknownExpr`.

## 4. Constraint generation

```kotlin
root.foo?.bar?.number()
```

emite:

```text
/foo     Object?
/foo/bar Number?
```

Operaciones añaden constraints:

```kotlin
expr gte 3
```

requiere numeric.

## 5. Unification

Reglas mínimas:

```text
Unknown ∩ T = T
T ∩ T = T
Integer ∩ Decimal = NumberUnion o Decimal según coercion model explícito
Text ∩ Number = contradiction
OpenObject + known field = OpenObject(refined)
ClosedObject + unknown field = contradiction
```

No inventar subtyping complejo antes de necesitarlo.

## 6. Observed samples

Inferencia debe ser conservadora.

Si sample A tiene field y B no:

```text
field optional
```

Si tipos difieren:

```text
Union(typeA,typeB)
```

No escoger “el más frecuente”.

## 7. Sample authority

Un inferred shape no rechaza por sí solo un nuevo field en runtime. Su función inicial:

- autocomplete;
- hints;
- conflicts;
- documentation.

Promover a closed schema exige decisión explícita.

## 8. Formal schema

Adapters iniciales posibles:

- JSON Schema;
- OpenAPI schema;
- Kotlin `@PolicyShape` models via KSP.

Todo converge a `ShapeModel`; el compiler plugin no conoce el origen del schema.

## 9. Typo detection

Sólo CLOSED puede afirmar:

```text
replicass does not exist
```

OPEN no debe fingirlo.

OBSERVED puede emitir warning/suggestion, nunca compile error por ausencia en samples solamente.

## 10. Diagnostics de contradicción

```text
POLC041 Conflicting expectations for /spec/replicas:
  line 12 expects NUMBER
  line 28 expects TEXT
```

## 11. Runtime

Shape no sustituye evaluación. Incluso una policy compilada contra CLOSED schema puede recibir un recurso inválido; decode/schema validation produce typed errors/fail-closed según deployment.
