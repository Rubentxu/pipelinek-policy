# Modelo de datos, paths y shapes graduales

## 1. `ValueNode`

```kotlin
sealed interface ValueNode {
    data class Object(val entries: PersistentMap<String, ValueNode>) : ValueNode
    data class Array(val elements: PersistentList<ValueNode>) : ValueNode
    data class Text(val value: String) : ValueNode
    data class Integer(val value: BigInteger) : ValueNode
    data class Decimal(val value: BigDecimal) : ValueNode
    data class Boolean(val value: kotlin.Boolean) : ValueNode
    data object Null : ValueNode
}
```

No incluye source location para que igualdad semántica no dependa del formato/origen.

## 2. `ResourceDocument`

```kotlin
data class ResourceDocument(
    val id: ResourceId,
    val format: ResourceFormat,
    val root: ValueNode,
    val sourceMap: SourceMap,
    val attributes: ResourceAttributes
)
```

`attributes` es metadata opcional:

```text
kind
name
namespace
tags
mediaType
originRef
```

No se exige Kubernetes shape.

## 3. Node identity

Cada decoder asigna `NodeId` estable dentro del documento parseado.

```kotlin
@JvmInline value class NodeId(val value: Long)
```

`SourceMap`:

```text
NodeId -> SourceAnchor
```

## 4. Source anchors

```kotlin
sealed interface SourceAnchor {
    data class TextSpan(...): SourceAnchor
    data class Cell(row: Long, column: String): SourceAnchor
    data class Element(elementId: String): SourceAnchor
    data class Logical(path: DocumentPath): SourceAnchor
}
```

Esto permite JSON/YAML/CSV/DrawIO/API sin forzar línea/columna universal.

## 5. `DocumentPath`

```kotlin
sealed interface PathSegment {
    data class Key(val value: String): PathSegment
    data class Index(val value: Int): PathSegment
}

data class DocumentPath(val segments: PersistentList<PathSegment>)
```

Canonical text representation recomendada: JSON Pointer-like.

## 6. `Selector`

`DocumentPath` apunta a una posición concreta. `Selector` puede devolver múltiples nodos.

```text
Selector
├── Root
├── Field(parent,key)
├── Index(parent,n)
├── Each(parent)
├── Entries(parent)
├── Values(parent)
├── Where(parent,predicate)
└── Descendants(parent)   # diferido hasta caso real
```

## 7. Missing, Null y type mismatch

Deben ser distinguibles:

```kotlin
sealed interface Selection<out T> {
    data class Present<T>(val value: T): Selection<T>
    data object Missing: Selection<Nothing>
    data object NullValue: Selection<Nothing>
    data class TypeMismatch(val expected: ValueType, val actual: ValueType): Selection<Nothing>
}
```

No coerción implícita de `"3"` a número.

## 8. Shape model

```text
Shape
├── OpenObject(known fields + additional unknown fields)
├── ClosedObject(known fields only)
├── Array(element shape)
├── Text
├── Integer
├── Decimal
├── Boolean
├── Null
├── Union
└── Unknown
```

## 9. Tres autoridades de shape

```text
OPEN      no schema; arbitrary key valid -> UnknownExpr
OBSERVED  inferred samples/policy usage -> diagnostics/autocomplete hints
CLOSED    formal schema/model -> unknown key compile error
```

`ShapeAuthority` debe quedar grabado en metadata del bundle.

## 10. Policy-induced constraints

La policy puede inferir constraints sin schema:

```kotlin
root.spec?.replicas?.number()
```

genera:

```text
/spec          : Object?
/spec/replicas : Number?
```

Si otra expresión exige texto sobre la misma ruta y no hay unión explícita:

```text
Number ∩ Text = contradiction
```

compile diagnostic.

## 11. Schema unification

Los constraints derivados de policy se unifican con schemas externos si existen.

```text
policy expects Number
schema declares String
-> compile error
```

En `OBSERVED`, contradicciones pueden ser warning configurable durante prototipado, pero el bundle production no acepta contradictions sin resolución explícita.

## 12. Collections y datasets

Un decoder produce `Sequence<ResourceDocument>`.

CSV puede funcionar como:

- `WHOLE_DOCUMENT`: root = array rows;
- `EACH_ROW`: cada fila = documento.

JSONL: naturalmente `EACH_RECORD`.

Datasets permiten agrupar recursos nombrados sin añadir semántica de formato.
