# M9 Design — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Phase:** design

## 1. Módulo y estructura

Módulo Gradle nuevo `pipelinek-policy-cli` (application plugin, `mainClass
= com.pipelinek.policy.cli.PolicyCliKt`). Depende de: root (kernel, dsl,
ir, bundle), `policy-decoders-json/yaml/csv`. **Cero dependencias nuevas**
(argparse manual, JSON manual — mismo estilo que CanonicalPolicyJson).

```
pipelinek-policy-cli/src/main/kotlin/com/pipelinek/policy/cli/
  PolicyCli.kt        — main + dispatch
  CliArgs.kt          — parser posicional/flags minimal
  ExitCodes.kt        — constantes 0/1/2/3
  JsonHelp.kt         — descriptores --json-help (derivados del registry)
  CommandRegistry.kt  — un solo source of truth: subcomandos + flags + help
  Findings.kt         — Finding ADT + emitters text/json/jsonl
  commands/
    CompileCmd.kt  CheckCmd.kt  DiffCmd.kt  ExplainCmd.kt
    InspectCmd.kt  ShapeCmd.kt  TestCmd.kt
    BundleCmd.kt   (verify delega en BundleVerifier)
```

## 2. Decisiones clave

### 2.1 Dispatch y help derivados del mismo registry

`CommandRegistry` define, por subcomando: nombre, flags aceptados,
descripción y exit codes relevantes. `PolicyCli` dispatch y `--json-help`
se generan del registry ⇒ imposible divergencia help/dispatch (caza 05b
por construcción, pero el test de falsificación igual se escribe).

### 2.2 Exit codes (contrato estable)

| Code | Significado |
|---|---|
| 0 | éxito sin violations |
| 1 | violations presentes (check/test/diff findings) |
| 2 | usage error, refusal, decode error, formato desconocido |
| 3 | error tipado del evaluador/engine o error interno inesperado |

Agregación check: error(refusal/decode) > violation > ok. Precedencia
explícita y testeada (02a corregido).

### 2.3 Finding ADT (contrato Agent UAT)

```kotlin
data class Finding(
  policyId, ruleId, resourceId, severity,
  location: Location?,           // path + line/cell si el decoder lo da
  state: Pass|Violation|Waived|Refusal|Error,
  remediation: String,           // obligatorio, nunca vacío
  fingerprint: String            // ViolationFingerprint M7 reutilizado
)
```

JSONL = un Finding serializado por línea. JSON = `{summary, findings[]}`.
El fingerprint reutiliza `ViolationFingerprint.of` (M7): una sola
definición de identidad de violación en el proyecto.

### 2.4 I/O exclusivamente en CLI

Los commands leen ficheros (fs) y llaman a las APIs puras:
- check: decoders SPI por extensión → Evaluator → Findings
- diff: PolicyDiff.of directamente (08d garantiza no-divergencia)
- inspect: CanonicalPolicyJson del bundle
- explain: Evaluator + árbol de expresión del IR (node ids)
- compile/bundle: packer M5 + BundleVerifier
- test: fixtures dir (nombre→expectation) vs resultado de check

### 2.5 Decoder por extensión, no por contenido

Registry extensión→decoderId. Extensión desconocida ⇒ refusal exit 2.
Un .csv nunca se sniff-ea como json (02b).

## 3. Testing

- Tests unitarios por command con archivos temp (junit5 @TempDir).
- Un test de integración ejecuta el main real (`main(args)`) capturando
  exit code vía excepción/valor de retorno: `PolicyCli.run(args): Int`
  (main solo `exitProcess(run(args))`) ⇒ testeable sin fork.
- 09a: test arquitectónico que escanea imports `java.io|java.nio|java.net|
  Process|Runtime` en `kernel|ir|bundle|dsl` (baseline actual: solo
  clases ya auditadas; el test fija la baseline exacta).

## 4. Riesgos y mitigaciones

- **Tamaño del cambio**: 8 comandos es mucho surface ⇒ WUs separados por
  grupo (infra+registry, check+findings, diff/inspect/shape, explain,
  compile/bundle/test, help+exit matrix).
- **Explain con expression trace**: Evaluator puede no exponer trazas hoy.
  Mitigación: v1 de explain muestra el subárbol de expresión del IR con
  estado agregado por nodo si el evaluator lo expone; si no, estado de la
  rule + expression source. Se inspecciona en WU y se ajusta scope sin
  cambiar el contrato (ruleId + árbol culpable identificable).

## 5. Paridad y leyes

- Law 5 intacta: kernel sigue sin I/O (09a la fija con test).
- Law 7: Findings es un ADT tipado; el JSON se genera en el CLI.
- Ningún cambio semántico en evaluación: el CLI es una capa de superficie.
