# Integración como plugin externo de PipelineK

## 1. Principio

`pipelinek-policy` debe instalarse por el mismo seam público que cualquier plugin externo.

```text
external JAR
  -> plugin manifest
  -> admission
  -> frozen registries
  -> Step/Event definitions
```

Cero branches en coordinator.

## 2. Primer Step

Stable key propuesto:

```text
policy.check
```

Input conceptual:

```kotlin
data class PolicyCheckRequest(
    val bundle: PolicyBundleRef,
    val resources: List<ResourceInputRef>,
    val datasets: List<DatasetInputRef>,
    val deployment: PolicyDeploymentConfig,
    val options: PolicyCheckOptions
)
```

Output:

```kotlin
data class PolicyCheckResult(
    val report: PolicyReport,
    val decision: PolicyDecision
)
```

Nada de `Map<String, Any?>` público.

## 3. Effects/capabilities

El evaluator puro no necesita capabilities.

El Step adapter puede necesitar sólo las mínimas para resolver inputs:

```text
FILESYSTEM_READ      si bundle/resources vienen del workspace
ARTIFACT_READ        si se integran artifacts tipados
```

Network/credentials deben entrar por otros Steps/facts; no por el evaluator.

## 4. DSL PipelineK

```kotlin
stage("Policy") {
    val result = policyCheck {
        bundle("policies/acme-release.pkpolicy")
        resource(yaml("deploy/deployment.yaml"))
        resource(json("build/sbom.json"))
        enforce()
    }
}
```

Esta fachada baja al Step contract estable.

## 5. Events

Primer catálogo mínimo:

```text
policy.evaluation.started
policy.violation.detected
policy.waiver.applied
policy.evaluation.completed
```

No emitir event por cada PASS.

No transportar documentos completos ni secretos.

Cada event incluye refs/digests y causation/correlation de PipelineK según SDK.

## 6. Enforcement mapping

`PolicyDecision`:

```text
Proceed
ProceedWithWarnings
OverrideRequired
Reject
```

El Step handler traduce esto a su StepOutcome conforme al contrato público del SDK. El Event Plane no decide outcome.

## 7. Replay

Fingerprint mínimo:

```text
bundle digest
resource digests
dataset digests
waiver-set digest
deployment config digest
engine version / IR semantics version
explicit evaluation context facts
```

Si todo coincide, el resultado es candidato natural a reuse según capabilities del Step SDK.

## 8. Certificación externa

Debe existir un consumidor/fixture fuera de `pipeline-kotlin` que:

- construya el plugin contra artifacts publicados del mismo SHA candidato;
- instale plugin JAR;
- compile `.pipeline.kts` usando façade;
- ejecute `policy.check` real;
- observe plugin events;
- verifique que core no contiene referencias a `policy.check`.

## 9. Regla de STOP

Si implementar una policy feature exige:

- branch de StepKey en coordinator;
- cambio específico en compiler de PipelineK;
- acceso a internals no publicados;

STOP y abrir ADR sobre capability genérica del Plugin SDK. No añadir excepción policy-specific.
