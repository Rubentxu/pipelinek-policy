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
