# Bootstrap recomendado del nuevo repositorio

## 1. Nombre

Nombre canónico en estos documentos:

```text
pipelinek-policy
```

El rename es barato antes de publicar coordenadas; después, `bundleId` y packages deben ser estables.

## 2. Módulos físicos iniciales

No materializar todos los bounded contexts como módulos desde el día 1. Primera topología:

```text
pipelinek-policy/
├── policy-core/               # ValueTree + Policy domain + evaluator + IR
├── policy-decoder-api/        # ResourceDocument/Decoder SPI/SourceMap contracts
├── policy-decoders/           # json/yaml/csv adapters al principio juntos
├── policy-dsl/                # explicit Kotlin DSL + context parameters
├── policy-compiler/           # FIR plugin (entra en M4, puede estar vacío antes)
├── policy-bundle/             # entra en M5
├── policy-cli/                # entra en M5/M9, mínimo al principio
├── pipelinek-policy-plugin/   # se crea en M6, no antes
└── policy-testkit/
```

Regla de split: separar un módulo sólo cuando exista una frontera de dependencia/compatibilidad que el build pueda hacer cumplir.

## 3. Stack inicial

```text
Kotlin          2.4.x exact pinned
JDK             21
Gradle          compatible con Kotlin seleccionado
JUnit 5         tests
Kotest property opcional o jqwik (decidir M1 tras spike mínimo)
kotlinx.serialization para codecs Kotlin-owned
Jackson/SnakeYAML sólo en decoder adapters si resultan mejores que alternativas
```

No introducir Arrow como dependencia estructural antes del spike M1.

## 4. Dependency graph inicial

```text
policy-core
   ↑
policy-decoder-api
   ↑              ↑
policy-decoders  policy-dsl
                    ↑
              policy-compiler

policy-bundle -> policy-core
policy-cli    -> core + decoders + bundle + dsl/compiler tooling
```

`pipelinek-policy-plugin` dependerá de artifacts públicos de PipelineK + application APIs de este repo, nunca al revés.

## 5. Primeros commits sugeridos

```text
chore(repo): bootstrap kotlin policy workspace

docs(architecture): adopt universal value tree and pure policy ir

test(compat): import structured-resource characterization corpus

feat(core): add canonical structured value tree

feat(core): add selector and evaluation algebra

feat(core): add pure policy evaluator
```

## 6. Primera demo que debe funcionar

Sin YAML todavía:

```kotlin
val resource = valueObject(
    "spec" to valueObject(
        "replicas" to valueInteger(2)
    )
)

val policy = policySet("demo") {
    rule("replicas") {
        require {
            root.field("spec")
                .field("replicas")
                .asNumber() gte 3
        }
    }
}

val report = evaluate(policy, resource)
```

Resultado: una `PolicyViolation` determinista.

La segunda demo añade JSON/YAML parity. La tercera añade FIR sugar. No invertir el orden.
