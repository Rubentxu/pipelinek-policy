# ADR-0008 — Integración PipelineK exclusivamente por Plugin SDK público

**Status:** ACCEPTED

## Decisión

`pipelinek-policy` vive en repo separado y entra por manifest/contributors públicos.

Primer Step: `policy.check`.

## No-core-change law

Una nueva policy capability que encaje en shapes públicos debe requerir:

```text
0 cambios semantic dispatch
0 coordinator branches
0 StepKey-specific compiler branches
```

Si no es posible, STOP y ADR sobre capability genérica del SDK.
