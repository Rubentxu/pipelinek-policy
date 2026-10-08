# M10 CSV 1 GiB Receipt (on-demand, 05c)

```text
cert-csv-receipt/v1
bytes: 1073741832
rows: 12741111
generate_ms: 6531
decode_ms: 38087
evaluate_ms: 450165
report_stream_sha256: 33c9306508009c59f983dcda0fa5890af48322c06f5aa4fb9aab3edcdc372c16
```

## Condiciones de ejecución (OBSERVED)

- JDK: Temurin 21.0.8+9 (toolchain), heap -Xmx24g (OOM a 6g y 12g:
  full-materialize de 12.7M filas × 6 celdas + sourceMap por celda).
- Máquina: 94 GiB RAM (51 disponible).
- Comando: `./gradle-jdk21.sh :pipelinek-policy-cli:certCsv1gib -PcertCsvBytes=1073741824`
- Wall: ~8m20s total (generate 6.5s, decode 38s, evaluate 450s).

## Presupuesto documentado (spec 05c)

| Fase | Tiempo | Nota |
|---|---|---|
| generate | 6.5 s | streaming, barato |
| decode | 38 s | full-materialize: ~12.7M rows |
| evaluate | 450 s | 1 regla × 12.7M filas, digest por reporte (~35µs/fila) |
| heap | 12-24 GiB | límite observado: 6g y 12g insuficientes |

## Hallazgo de caracterización (para M8)

El CSV decoder es full-materialize: 1 GiB ⇒ ~76M ValueNode + sourceMap
por celda ⇒ 12-24 GiB heap. El presupuesto de streaming de M8
(LOCAL/AGGREGATE/GLOBAL) es el camino para bajar esto; queda
registrado como INPUT para M8, no como deuda de M10 (el gate pide
characterization, no optimización).
