# ADR-0006 — Decoder SPI + SourceMap preservado durante parsing

**Status:** ACCEPTED

## Decisión

Cada formato entra por `ResourceDecoder` y produce `ResourceDocument + ValueTree + SourceMap`.

La localización no se reconstruye primariamente después mediante búsqueda de valores.

## Consecuencias

- YAML/JSON → text spans;
- CSV → cell anchors;
- DrawIO → element anchors;
- igualdad semántica desacoplada de ubicación.
