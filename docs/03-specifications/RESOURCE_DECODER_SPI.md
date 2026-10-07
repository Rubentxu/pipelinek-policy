# ResourceDecoder SPI

## 1. Objetivo

Transformar cualquier representación física a `ResourceDocument` sin filtrar detalles de formato al evaluator.

## 2. API conceptual

```kotlin
interface ResourceDecoder {
    val descriptor: DecoderDescriptor

    fun decode(
        input: ResourceInput,
        options: DecodeOptions
    ): DecodeResult
}
```

```kotlin
sealed interface DecodeResult {
    data class Documents(val documents: Sequence<ResourceDocument>): DecodeResult
    data class Refused(val errors: NonEmptyDecodeErrors): DecodeResult
}
```

## 3. Inputs

```text
Bytes
Chars
MapValue (adapter Kotlin)
StreamHandle en capa impura -> chunks suministrados al decoder
```

El decoder core no abre paths por sí mismo.

## 4. JSON

- preservar integer vs decimal;
- duplicate keys: política explícita, por defecto refuse;
- source spans;
- document único o top-level array configurable.

## 5. YAML

- multi-document -> múltiples `ResourceDocument`;
- anchors/aliases se resuelven con límites anti-expansion;
- duplicate keys refuse;
- source spans;
- tags custom: refuse o adapter explícito, nunca coerción silenciosa.

## 6. CSV

Modos:

```text
WHOLE_DOCUMENT
EACH_ROW
```

Config:

```text
header present/explicit
separator
quote
charset
column type inference mode
```

SourceAnchor por celda.

Type inference no debe cambiar silenciosamente a mitad del dataset. Dos modos:

```text
TEXT_ONLY (default seguro)
INFER_WITH_SAMPLE(N) + frozen inferred schema
```

## 7. Kotlin Map adapter

`Map<String, Any?>` se convierte recursivamente.

Tipos no soportados -> `DecodeRefusal.UnsupportedHostValue`, no `toString()` silencioso.

## 8. Parser contributors

```kotlin
interface ResourceDecoderContributor {
    val decoders: List<ResourceDecoder>
}
```

Registry se compone/freeze al startup de la aplicación/CLI, no por operación.

## 9. SourceMap laws

- cada node originado en el documento tiene anchor o razón explícita `Synthetic`;
- path lookup no usa búsqueda por valor como estrategia principal;
- two equal semantic ValueTrees pueden tener SourceMaps distintos;
- SourceMap no participa en equality del ValueTree.

## 10. Fitness

`policy-engine` no puede importar parsers concretos.
