# Oportunidades laterales — fuera de v1 salvo evidencia

Estas ideas son compatibles con la arquitectura, pero no forman parte del camino crítico inicial.

## 1. Policy IDE intelligence

Como FIR ya conoce paths/constraints:

- hover con inferred shape;
- explain inline;
- quick-fix para typo CLOSED;
- navegar desde violation a rule y desde rule a path.

No construir plugin IDE separado antes de demostrar que FIR/IntelliJ ya cubre suficiente.

## 2. Policy dependency graph

Derivar del IR:

```text
rule -> paths -> datasets -> functions -> enforcement layer
```

Útil para:

- impact analysis;
- identificar qué policies cambia un schema;
- agentes;
- visualización.

## 3. Incremental evaluation

Si cambia sólo `/spec/replicas`, reevaluar rules que leen ese path. Requiere path dependency index ya derivable del IR.

No implementar antes de tener workloads que lo justifiquen.

## 4. WASM/native evaluator

PolicyIR permite un futuro interpreter/compiled target fuera de JVM sin cambiar authoring. No es objetivo v1.

## 5. SARIF adapter

`PolicyViolation` puede proyectarse a SARIF para GitHub/IDE/security ecosystems sin convertir SARIF en modelo interno.

## 6. OpenTelemetry decision spans

Emitir observabilidad sobre evaluation/bundle/rule counts en adapters, sin meter telemetry en pure evaluator.

## 7. Remediation patches

Una policy podría adjuntar una **propuesta declarativa** de remediation en el futuro. No aplicar mutaciones automáticamente en v1.

## 8. Historical policy simulation

Con corpus de reports/facts versionados, ejecutar policy N/N+1 y medir impacto antes de rollout. Prioridad alta después de M7.

## 9. CogniCode / Git facts

Adapters pueden transformar findings de arquitectura, connascence, smells o change metadata a ValueTree y aplicar exactamente el mismo engine.

## 10. Policy-as-contract para plugins PipelineK

Validar manifests de plugins, capability declarations y compatibility metadata con el propio engine, evitando hardcodear reglas administrativas en el host.
