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

El `check` standalone emite el schema base de B3 para cada finding de policy
(violation o error de evaluación). Los campos de identidad y diagnóstico son:

```text
violationId     fingerprint determinista del finding
policyId        policy que produjo el resultado
policySetId     policy set al que pertenece esa policy, o null en un refusal
ruleId          regla que produjo el resultado
bundleDigest    artifactDigest del manifest verificado
subjectRef      ResourceDocument.id del recurso evaluado
sourceAnchor    SourceAnchor estructurado del nodo, o null si no se resuelve
path            DocumentPath lógico de la evaluación
actual          render textual seguro, nullable
expected        render textual de la expectativa, nullable
message         diagnóstico original del evaluator
remediation     instrucción accionable
severity        severidad emitida por el productor del finding
```

Los tres campos de identidad son INDEPENDIENTES y corresponden a las tres
componentes de `RuleKey(policySetId, policyId, ruleId)`. `ruleId` es el id
simple de la regla, nunca la clave compuesta, y `policyId` es la policy
propietaria, nunca el id del policy set. Dos policies distintas que declaren el
mismo `ruleId` producen findings distintos y direccionables.

`sourceAnchor` usa un objeto tipado: `textSpan` lleva `startLine`,
`startColumn`, `endLine`, `endColumn`; `cell` lleva `row` y `column`; `element`
lleva `elementId`; `logical` lleva `path`. Los spans de texto son 1-indexados y
su extremo final es exclusivo. `actual` y `expected` son strings JSON escapados
o `null`; nunca se serializan objetos de dominio arbitrarios ni fragmentos JSON
aportados por el recurso.

`fingerprint` se deriva de los MISMOS componentes que el finding publica
(`policyId`, `ruleId`, `path` y el fingerprint del recurso). Por eso el digest
que una exención (`Waiver`) necesita para dirigirse a este finding se puede
recalcular íntegramente desde la superficie publicada, sin acceso al kernel.

Por compatibilidad, el CLI conserva también `resourceId`, `location`, `state` y
`fingerprint`. `violationId` es el alias estable de `fingerprint`, y
`subjectRef` referencia el mismo recurso que `resourceId`.

Cambio de contrato B3.6: `policySetId` es un campo AÑADIDO. Antes de B3.6,
`ruleId` publicaba la `RuleKey` compuesta con prefijo de longitud
(`3:uat5:alpha11:shared-rule`) y `policyId` publicaba el id del policy set. Un
consumidor que leyera `ruleId` como id simple leía una cadena compuesta, y uno
que usara `policyId` para dirigir una exención apuntaba a la policy equivocada.
El campo nuevo no elimina ni renombra ningún campo existente; lo que cambia es
el VALOR de `ruleId` y `policyId`, que pasa a ser el que su nombre declara.

`enforcement`, `rollout` y `waiverStatus` son metadatos de gobierno, no hechos
disponibles en la evaluación standalone de B3. Se incorporarán como extensión
cuando exista el contexto de enforcement/rollout/waivers de B4. El CLI B3 no
los inventa, no los infiere del exit code y no los emite como estados por defecto.
Esta separación está registrada en ADR-0013.

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

Definir y congelar la siguiente matriz. `1` está reservado únicamente para
errores de invocación; no es un cajón genérico para errores de ejecución.

```text
0 operación completada o policy decision permits
1 CLI invocation/usage error
2 policy violation or blocking policy diff
3 evaluation, configuration, plan, budget, or runtime error
4 bundle/resource admission or decoding refusal
5 canonical policy IR compilation error
```

Los fallos de invocación (argumentos requeridos ausentes, comando/operación
desconocidos o flags con valores no válidos) devuelven 1. No convertirlos en
éxito mediante defaults silenciosos.

Los fallos de admisión incluyen bundles ausentes/corruptos, recursos ausentes,
formatos sin decoder y rechazos de decodificación. Un error tipado del evaluator
nunca cuenta como policy violation, incluso al evaluar una fixture `.deny.`.

Al agregar resultados de varias reglas/recursos, la precedencia es admisión (4),
evaluación/runtime (3), violación (2), éxito (0). Una negativa de fixture por
expectativa incumplida devuelve 2; una fixture `.deny.` que viola la policy
devuelve 0. `test` sin fixtures reconocibles devuelve 1. `bundle verify` falla
con 4; `compile` devuelve 5 cuando no puede admitir/compilar el IR canónico.
`diff` devuelve 2 si encuentra entradas de diff que bloquean, y 0 si el diff
está vacío.
