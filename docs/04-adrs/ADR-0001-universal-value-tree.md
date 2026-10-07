# ADR-0001 — ValueTree universal como modelo de facts

**Status:** ACCEPTED

## Contexto

La referencia soporta YAML/JSON/CSV/DrawIO porque los normaliza antes de aplicar rules. Un nuevo motor no debe privilegiar Kubernetes ni POJOs.

## Decisión

Toda evaluación opera sobre `ResourceDocument.root: ValueNode`, con ADT cerrado `Object/Array/Text/Integer/Decimal/Boolean/Null`.

`Map<String, Any?>` sólo existe en adapters.

## Consecuencias

- la misma policy funciona sobre formatos equivalentes;
- evaluator independiente de parser libraries;
- se puede serializar/fingerprint determinísticamente;
- tipos de dominio (SemVer, Quantity) son proyecciones/funciones, no nodos base.

## Rechazado

- `Map<String, Any?>` como core;
- modelos Kotlin obligatorios;
- JSON como modelo nominal.
