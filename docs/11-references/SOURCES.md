# Fuentes y referencias de investigación

Fecha de investigación del paquete: **2026-10-07**.

## Repositorios propios inspeccionados

### `Rubentxu/framework_modular`

Rutas relevantes:

- `modules/policies/src/es/giss/framework/policies/AdmissionController.groovy`
- `modules/policies/src/es/giss/framework/policies/ResourceInspector.groovy`
- `modules/policies/src/es/giss/framework/policies/PolicyValidator.groovy`
- `modules/policies/src/es/giss/framework/policies/parser/JsonInputModelParser.groovy`
- `modules/policies/src/es/giss/framework/policies/parser/YamlInputModelParser.groovy`
- `modules/policies/src/es/giss/framework/policies/parser/CSVInputModelParser.groovy`
- `modules/policies/src/es/giss/framework/policies/location/FieldEnricher.groovy`
- `modules/policies/test/groovy/es/giss/framework/policies/AdmissionControllerSpec.groovy`
- `modules/policies/test/groovy/es/giss/framework/policies/ResourceInspectorSpec.groovy`
- `modules/core/src/es/giss/framework/core/interfaces/StructuredResource.groovy`
- `modules/core/src/es/giss/framework/core/vo/GenericResource.groovy`
- `modules/core/src/es/giss/framework/core/utils/MapUtils.groovy`
- `modules/core/src/es/giss/framework/core/storage/ObjectCreator.groovy`

Hallazgo clave: el soporte multiformato ya converge a un modelo map-like; el acceso estilo `obj.foo` y ejecución dinámica los proporciona Groovy/GroovyShell, no `MapUtils`.

### `Rubentxu/pipeline-kotlin`

Rutas relevantes:

- `docs/v2/03-specifications/STEP_PLUGIN_SDK.md`
- `docs/pipelinek-semantic-evolution/05-step-plugin-sdk-v2.md`
- `docs/v2/06-design/POLICY_GUARDRAILS_DESIGN.md`
- `docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md`
- `examples/example-uppercase-plugin/`

Principios reutilizados: external plugin seam, manifest/registry freeze, no-core-change law, typed codecs/events, separación Policy Guardrail Cedar vs observers.

## Kotlin oficial

- Context parameters: https://kotlinlang.org/docs/context-parameters.html
- Kotlin 2.4: https://kotlinlang.org/docs/whatsnew24.html
- Custom compiler plugins/FIR: https://kotlinlang.org/docs/custom-compiler-plugins.html
- Compiler plugins overview: https://kotlinlang.org/docs/compiler-plugins-overview.html
- KSP overview: https://kotlinlang.org/docs/ksp-overview.html
- `context()` API: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/context.html
- Kotlin DataFrame compiler plugin: https://kotlin.github.io/dataframe/compiler-plugin.html
- DataFrame extension properties: https://kotlin.github.io/dataframe/extensionpropertiesapi.html

## Policy engines / products

### OPA/Rego

- Policy language: https://www.openpolicyagent.org/docs/policy-language
- Bundles: https://www.openpolicyagent.org/docs/management-bundles
- Decision logs: https://www.openpolicyagent.org/docs/management-decision-logs

### Cedar

- Reference: https://docs.cedarpolicy.com/
- Security/no-I/O/schema guidance: https://docs.cedarpolicy.com/other/security.html
- Data types/extensions: https://docs.cedarpolicy.com/policies/syntax-datatypes.html

### Sentinel

- Introduction: https://developer.hashicorp.com/sentinel/docs/intro
- Enforcement levels: https://developer.hashicorp.com/sentinel/docs/concepts/enforcement-levels

### Kyverno

- Documentation: https://kyverno.io/docs/
- Policy exceptions: https://kyverno.io/docs/exceptions/

### CEL

- Overview: https://cel.dev/overview/cel-overview

### CUE

- Validation tour: https://cuelang.org/docs/tour/basics/validation/

### JSON Schema

- Documentation: https://json-schema.org/docs

## Uso de estas referencias

Las referencias se usan para contrastar ideas, no para copiar APIs. El contrato final de `pipelinek-policy` queda definido por estos documentos y por los futuros ADR/receipts del repositorio.
