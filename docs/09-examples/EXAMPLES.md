# Ejemplos de producto

## 1. Arbitrary map, sin schema

```kotlin
val generic = policySet("generic") {
    policy("service") {
        rule("owner-required") {
            require {
                root.metadata?.owner?.text()?.isNotBlank()
            }
            violation {
                at(root.metadata?.owner)
                message = "El recurso requiere owner"
            }
        }
    }
}
```

## 2. Kubernetes YAML/JSON indistinguible

```kotlin
rule("minimum-prod-replicas") {
    appliesWhen {
        allOf(
            root.kind?.text() eq "Deployment",
            root.metadata?.labels?.environment?.text() eq "production"
        )
    }

    require {
        root.spec?.replicas?.number() gte 3
    }
}
```

## 3. Containers

```kotlin
rule("no-latest") {
    forbid {
        root.spec?.template?.spec?.containers?.array()?.any { container ->
            container.image?.text()?.endsWith(":latest")
        }
    }
}
```

## 4. CSV row

```kotlin
policy("employees") {
    rule("active-adult") {
        appliesWhen { root.active?.boolean() eq true }
        require { root.age?.number() gte 18 }
        violation {
            at(root.age)
            message = "Empleado activo menor de edad"
        }
    }
}
```

## 5. SemVer

```kotlin
rule("approved-runtime") {
    require {
        root.runtime?.version?.text()?.semver() gte semver("21.0.0")
    }
}
```

## 6. Supply chain

```kotlin
rule("no-gpl") {
    forbid {
        root.components?.array()?.any { component ->
            component.licenses?.array()?.any { license ->
                license.id?.text() eq "GPL-3.0"
            }
        }
    }
}
```

## 7. PipelineK

```kotlin
stage("Policy") {
    val result = policyCheck {
        bundle("policy/acme.pkpolicy")
        resource(yaml("deploy/app.yaml"))
        resource(json("build/sbom.json"))
        enforce()
    }
}
```

## 8. CLI (M9): check accionable por agentes

```bash
# Compilar una política (IR canónico JSON) a bundle reproducible
policy-cli compile policy.json --out acme.pkpolicy

# Evaluar un corpus y emitir findings JSONL (una línea = un finding accionable)
policy-cli check acme.pkpolicy corpus/ --format jsonl

# Cada línea JSONL lleva: policyId, ruleId, resourceId, severity, state,
# location (fichero/línea/celda), remediation y fingerprint:
# {"policyId":"acme","ruleId":"no-gpl","resourceId":"sbom.json", ...,
#  "location":{"file":"build/sbom.json","cell":{"row":3,"column":5}},
#  "remediation":"...","fingerprint":"sha256:..."}

# Otros comandos: test (fixtures allow/deny), diff (dos corpora),
# explain (regla + árbol), inspect (IR canónico), shape (conteos),
# bundle verify (integridad), --json-help (autodescubrimiento para agentes)
```

Exit codes estables, congelados por ADR-0012 y verificados contra `ExitCodes.kt`:
`0` OK, `1` error de uso/invocación, `2` violaciones (o diff de política no vacío),
`3` error de evaluación/configuración/presupuesto, `4` rechazo de admisión o
decodificación, `5` error de compilación del IR canónico.

```kotlin
// pipelinek-policy-cli/src/main/kotlin/com/pipelinek/policy/cli/ExitCodes.kt
OK = 0; USAGE = 1; VIOLATIONS = 2
EVALUATION_ERROR = 3; ADMISSION_ERROR = 4; COMPILER_ERROR = 5
```

> Corregido el 2026-10-10 (B6.9): esta sección decía `0/1/2/3` con
> `1` = violaciones y `2` = uso, lo que está **invertido** respecto a ADR-0012 y
> al código. Un agente que ramificara sobre ella habría tratado una violación
> como error de invocación. No había test que lo detectara: los exit codes sí
> están fijados por tests, pero la documentación no estaba atada a ellos.

Los comandos citados existen en el `CommandRegistry`
(verificado por cross-check en `HelpAndExitCodesTest` 05b).
