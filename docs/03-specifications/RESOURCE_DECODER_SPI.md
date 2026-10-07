# ResourceDecoder SPI

## 1. Objetivo

Transformar cualquier representación física a `ResourceDocument` sin filtrar detalles de formato al evaluator.

## 2. API conceptual

> **M2 WU-4 anchor (2026-10):** the conceptual API below was a placeholder.
> The canonical types now live in
> `src/main/kotlin/com/pipelinek/policy/decoder/`. The conceptual sketch
> is kept for traceability; the binding table at the end of this section
> maps each conceptual name to the concrete Kotlin type.

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

### 2.1 Concrete type bindings

| Conceptual name        | Concrete type (package `com.pipelinek.policy.decoder`) |
|-----------------------|---------------------------------------------------------|
| `ResourceInput`       | `ByteArray` (byte-driven SPI; `MapAdapterDecoder` additionally exposes `decodeMap(Map<String, Any?>)` and refuses `decode(ByteArray)` with `UNSUPPORTED_HOST_VALUE`) |
| `DecodeOptions`       | `data class DecodeOptions(csvMode, inferSampleSize, yamlAliasCap)` with `init { require(...) }` guards |
| `DecodeResult.Documents` | `DecodeResult.Ok(documents: List<ResourceDocument>)` |
| `DecodeResult.Refused` | `DecodeResult.Refused(refusal: DecodeRefusal)` |
| `NonEmptyDecodeErrors` | `DecodeRefusal(code: DecodeRefusalCode, anchor: SourceAnchor?)` (single error per decode by design; no error list — the refusal is atomic) |
| `ResourceDocument`    | `ResourceDocument(id, format, root: ValueNode, sourceMap: SourceMap, attributes: ResourceAttributes)` |
| `DecoderDescriptor`   | `DecoderDescriptor(format: ResourceFormat, version: String)` |

### 2.2 Refusal code taxonomy

`DecodeRefusalCode` (closed enum):

- `MALFORMED`         — input bytes could not be parsed (lexical / syntactic error).
- `DUPLICATE_KEY`     — mapping has two entries with the same key; the
                        decoder refuses rather than silently keeping the
                        last value (mutation gate item 2).
- `SCHEMA_FROZEN`     — CSV column inference froze a kind and a later row
                        violates it (mutation gate item 3).
- `ALIAS_EXPANSION_EXCEEDED` — YAML alias cap exceeded.
- `UNSUPPORTED_HOST_VALUE`   — `MapAdapterDecoder` saw a non-whitelisted carrier.

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

## 11. Mutation gate (M2 WU-4)

The decoder implementations are guarded by a four-item mutation gate so
that future regressions which silently change a behavior (coercion,
drop, swap) fail a targeted test. Each item asserts the swap would
have made the test PASS pre-fix and FAIL post-fix.

| # | Mutation                                             | Test that locks it                                                                                                        |
| - | ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| 1 | CSV default mode coerces numeric-looking cells       | `CsvDecoderTest.\`default TEXT_ONLY yields TextValue for numeric-looking cells\``                                          |
| 2 | JSON silent keep-last on duplicate keys              | `JsonDecoderTest.\`duplicate key fails closed with Refused DUPLICATE_KEY\``                                                |
| 3 | CSV `SCHEMA_FROZEN` check is removed from `decode`    | `CsvDecoderTest.\`INFER_WITH_SAMPLE 3 refuses heterogeneous row 4 with SCHEMA_FROZEN\``                                     |
| 4 | `canonicalDigest` starts including `SourceMap`        | `CanonicalDigestTest.\`equal trees with different SourceMap produce equal digests\``                                        |

Item 4 lives in the kernel tests; items 1–3 live in the parser
submodule tests. The gate is documented here so any future parser
addition is expected to add a 5th+ entry that captures the equivalent
silent-coercion / drop / swap surface for the new format.
