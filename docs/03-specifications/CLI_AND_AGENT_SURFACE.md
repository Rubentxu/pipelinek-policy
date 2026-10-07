# CLI y superficie para agentes

## 1. Principio

El CLI no es un renderer de logs; expone el modelo estructurado del engine.

## 2. Comandos iniciales

```text
pipelinek-policy compile
pipelinek-policy check
pipelinek-policy explain
pipelinek-policy inspect
pipelinek-policy diff
pipelinek-policy test
pipelinek-policy shape
pipelinek-policy bundle verify
```

## 3. Autodescubrimiento estilo HATEOAS

Cada comando debe ofrecer:

```text
--help
--json-help
```

`--json-help` devuelve capabilities/next actions machine-readable:

```json
{
  "command": "check",
  "accepts": ["bundle", "resource", "dataset"],
  "formats": ["text", "json", "jsonl"],
  "related": [
    {"command":"explain", "when":"need rule details"},
    {"command":"diff", "when":"compare bundles"}
  ]
}
```

No requiere MCP.

## 4. Check

```bash
pipelinek-policy check \
  --bundle acme.pkpolicy \
  --resource deployment.yaml \
  --format jsonl
```

## 5. Agent output

Una violation debe incluir:

```text
violationId
policyId
ruleId
bundleDigest
subjectRef
sourceAnchor
path
actual (safe rendered)
expected
message
remediation
severity
enforcement
rollout
waiverStatus
```

## 6. Explain

```bash
pipelinek-policy explain acme.kubernetes/minimum-replicas
```

Salida estructurada:

- paths leídos;
- expected types;
- applicability;
- assertion;
- params;
- pure functions usadas;
- static cost;
- source location de policy.

## 7. Inspect

Sobre bundle:

```text
policy count
rule count
unique paths
function requirements
shape authorities
GLOBAL/AGGREGATE rules
bundle provenance
```

## 8. Shape

```bash
pipelinek-policy shape infer fixtures/*.yaml fixtures/*.json
pipelinek-policy shape explain deployment
```

El shape inferido es hint, no schema authority salvo promoción explícita.

## 9. Exit codes

Definir y congelar:

```text
0 policy decision permits
2 policy violation blocks
3 evaluation/configuration error
4 bundle/admission error
5 compiler error
```

No usar 1 genérico para todo.
