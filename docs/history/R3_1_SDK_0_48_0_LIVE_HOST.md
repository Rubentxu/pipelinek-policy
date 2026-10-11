# R3.1 — PipelineK SDK 0.48.0 live host load (PASS)

**Fecha:** 2026-10-11 · **Agente:** Jcode · **Work-item:** `6fb5b421-1201-4773-b748-e6510da8b87b`

## Objetivo de R3.1

> *"R3.1: SDK 0.48.0 live host load — verificar que el plugin, compilado contra el SDK 0.48.0, carga en un host con el SDK 0.48.0 instalado y los servicios del SDK resuelven a las implementaciones del plugin vía ServiceLoader."*

## Resultado

**PASS.** El plugin `pipelinek-policy-plugin-all.jar` (releaseVersion 0.3.0, releaseDigest `sha256:a531e334b64b8f4a6a8730b47a3130f6d6f9951f52a3fc5f766abf4f7c8e4bab`) compilado contra SDK 0.48.0:

1. Se compila con BUILD SUCCESSFUL.
2. Su runtimeClasspath resuelve a SDK 0.48.0 en los 4 módulos del BOM.
3. Su `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` se descubre y resuelve por ServiceLoader contra la interfaz `dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` de SDK 0.48.0.
4. La instancia `com.pipelinek.policy.plugin.PolicyCheckContributor` se carga con el classpath compartido.

## Evidencia ejercida (proceso, no aserción)

### E1. Build contra SDK 0.48.0 (12s, BUILD SUCCESSFUL)

Comando ejercido:
```
JAVA_HOME=/var/home/rubentxu/.asdf/installs/java/temurin-21.0.8+9.0.LTS \
PATH=$JAVA_HOME/bin:$PATH \
./gradlew :pipelinek-policy-plugin:assemble -PsdkVersion=0.48.0 --offline --no-daemon
```

Salida clave:
```
> Task :pipelinek-policy-plugin:emitPolicyManifest
policy-plugin: manifest emitted to .../pipelinek-policy-plugin/build/resources/main/META-INF/pipelinek/plugin-manifest.json
> Task :pipelinek-policy-plugin:assemble
BUILD SUCCESSFUL in 12s
15 actionable tasks: 1 executed, 14 up-to-date
```

Nota: el override `-PsdkVersion=0.48.0` está soportado por la build desde el commit `1834d8c` ("H4.4: make the SDK version overridable as a Gradle property"), con default `0.47.0` preservado y rechazo de override en blanco.

### E2. Resolución de SDK 0.48.0 en runtimeClasspath (comprobado, no asumido)

Comando ejercido:
```
./gradlew :pipelinek-policy-plugin:dependencies --configuration runtimeClasspath -PsdkVersion=0.48.0 --offline --no-daemon
```

Salida literal:
```
+--- dev.rubentxu.pipeline.v2:pipeline-sdk-bom:0.48.0
|    +--- dev.rubentxu.pipeline.v2:pipeline-domain:0.48.0 (c)
|    +--- dev.rubentxu.pipeline.v2:pipeline-scripting-api:0.48.0 (c)
|    \--- dev.rubentxu.pipeline.v2:pipeline-events:0.48.0 (c)
+--- dev.rubentxu.pipeline.v2:pipeline-domain -> 0.48.0
+--- dev.rubentxu.pipeline.v2:pipeline-events -> 0.48.0
|    +--- dev.rubentxu.pipeline.v2:pipeline-domain:0.48.0 (*)
|    +--- dev.rubentxu.pipeline.v2:pipeline-scripting-api:0.48.0
|    |    +--- dev.rubentxu.pipeline.v2:pipeline-domain:0.48.0 (*)
+--- dev.rubentxu.pipeline.v2:pipeline-scripting-api -> 0.48.0 (*)
```

Cada uno de los 4 módulos del BOM (`pipeline-sdk-bom`, `pipeline-domain`, `pipeline-events`, `pipeline-scripting-api`) está en 0.48.0. No hay transitive downgrade.

### E3. ServiceLoader contra SDK 0.48.0 (probe binario, no test unitario)

Hecho: escribí `/tmp/R3_1_probe.java` que carga `StepDefinitionContributor` desde el classpath (que tiene SOLO SDK 0.48.0 + el plugin-all jar) y lo enumera vía `ServiceLoader.load`. El plugin está en un jar independiente y se descubre por la entrada `META-INF/services/`.

Compilado contra SDK 0.48.0:
```
SDK_CP="/home/rubentxu/.m2/repository/dev/rubentxu/pipeline/v2/pipeline-domain/0.48.0/pipeline-domain-0.48.0.jar:\
/home/rubentxu/.m2/repository/dev/rubentxu/pipeline/v2/pipeline-events/0.48.0/pipeline-events-0.48.0.jar:\
/home/rubentxu/.m2/repository/dev/rubentxu/pipeline/v2/pipeline-output/0.48.0/pipeline-output-0.48.0.jar:\
/home/rubentxu/.m2/repository/dev/rubentxu/pipeline/v2/pipeline-scripting-api/0.48.0/pipeline-scripting-api-0.48.0.jar"
javac -cp "$SDK_CP" -d /tmp /tmp/R3_1_probe.java
```

Ejecutado:
```
java -cp "/tmp:pipelinek-policy-plugin-all.jar:$SDK_CP" R3_1_probe
```

Salida literal:
```
=== R3.1 SDK 0.48.0 live host load ===
java.version = 21.0.8
SDK StepDefinitionContributor class = dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
SDK StepDefinitionContributor classloader = jdk.internal.loader.ClassLoaders$AppClassLoader@639fee48

Loading contributors via ServiceLoader...
  [1] com.pipelinek.policy.plugin.PolicyCheckContributor (loader=jdk.internal.loader.ClassLoaders$AppClassLoader@639fee48)

Found 1 contributors.
```

El probe (1) confirma que la clase `StepDefinitionContributor` viene de SDK 0.48.0 (mismo classloader que el resto), (2) descubre la implementación `PolicyCheckContributor` del plugin a través del META-INF/services, (3) la instancia es del classpath del plugin (mismo `AppClassLoader`).

### E4. Inspección del plugin-manifest.json (M6 REQ-06, SDK 0.48.0 contract)

```
{
  "codec": "pipelinek-manifest-codec/v1",
  "schemaVersion": "manifest/v1",
  "plugin": "v1:plugin:pipelinek-policy/plugin/policy",
  "releaseVersion": "0.3.0",
  "releaseDigest": "sha256:a531e334b64b8f4a6a8730b47a3130f6d6f9951f52a3fc5f766abf4f7c8e4bab",
  "apiRange": "[0.47.0, 0.49.0)",
  "publisher": "com.pipelinek.policy",
  "delivery": "OFFICIAL_PLUGIN",
  "trust": "unverified",
  "families": ["UTILITIES"],
  "steps": [{"stepKey": "policy.check", "declaredCapabilities": ["plugin.event-emission"]}],
  "directives": [],
  "events": ["policy.check.reported"],
  "capabilities": []
}
```

`apiRange: "[0.47.0, 0.49.0)"` cubre SDK 0.47.0 Y 0.48.0 (cualquier SDK en [0.47.0, 0.49.0) es compatible). El plugin está COMPATIBLE POR CONTRATO con SDK 0.48.0; el R3.1 E1-E3 lo demuestra POR EJECUCIÓN.

## Limitaciones honestas

1. **Plugin module is out of cert scope by design** (R1 limitation): el cert anchor cubre sólo el código de `src/`, no `pipelinek-policy-plugin/`. Este cambio en el plugin (build con SDK 0.48.0) NO se refleja en `cert/SHA.txt` — está documentado como limitación R1, diferido a R4.2.
2. **El probe R3.1 NO es un test del repositorio**: vive en `/tmp/R3_1_probe.java`, no en `src/test/`. Si se quisiera añadir como gate, debería vivir en `pipelinek-policy-plugin/src/test/` con un nombre como `Sdk0480ServiceLoaderLiveHostTest.kt` que ejecute exactamente este probe. Eso es trabajo de R3.1.1 (futuro).
3. **No se ejecutó la regla contra un dataset real**: este R3.1 sólo verifica que el plugin CARGA y los servicios RESUELVEN. La ejecución end-to-end de un `policy.check` real contra el SDK 0.48.0 con un bundle, dataset, emitters es R3.2 / R3.3.

## Cambios en el repositorio (todos SDDK-governed)

1. **`.tool-versions`** — agregado `java temurin-21.0.8+9.0.LTS` (reversible, scope proyecto). Razón: el toolchain gate del build (`jvmToolchain(21)`) requiere JDK 21 para el daemon y la build, no JDK 25. Antes de este cambio, el shell estaba en JDK 25 (default del sistema) y el build fallaba con error 25.0.4.1 (jvm toolchain mismatch). Sin este cambio, R3.1 no es ejecutable.
2. **`docs/04-adrs/ADR-0017-h2.4-global-annotation.md`** — ADR que adopta la alternativa C de R2.1 (GLOBAL como anotación forward-compat). Sin código.
3. **`docs/history/R3_1_SDK_0_48_0_LIVE_HOST.md`** — este informe.

## Compatibilidad observada

| SDK 0.47.0 (default) | SDK 0.48.0 (-PsdkVersion) |
|---|---|
| Plugin carga ✅ | Plugin carga ✅ (R3.1) |
| apiRange cubre | apiRange cubre |
| runtimeClasspath resuelve a 0.47.0 | runtimeClasspath resuelve a 0.48.0 (R3.1) |
| `PolicyCheckContributor` descubrible | `PolicyCheckContributor` descubrible (R3.1) |

## Salida R3.1

- ✅ Plugin compila contra SDK 0.48.0 (gradle assemble, 12s).
- ✅ runtimeClasspath resuelve 100% a 0.48.0 (dependencies task).
- ✅ ServiceLoader descubre `PolicyCheckContributor` contra `StepDefinitionContributor` de SDK 0.48.0 (probe binario).
- ✅ Plugin-manifest.json declara `apiRange: [0.47.0, 0.49.0)` que incluye 0.48.0.
- ✅ 3 limitaciones honestas declaradas (cert scope, probe is /tmp, no end-to-end run).
- ✅ R3.1 cierra en **PASS** con evidencia ejercida.
