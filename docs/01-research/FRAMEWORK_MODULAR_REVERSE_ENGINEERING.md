# Ingeniería inversa — `framework_modular/modules/policies`

## 1. Qué hace realmente la referencia

La referencia no es sólo un validador YAML. Su arquitectura efectiva es:

```text
resource physical format
      │
      ▼
IResourceParser
      │
      ▼
StructuredResource / GenericResource
      │
      ▼
Map-like model (`toMap`, `spec`, `status`)
      │
      ▼
ResourceInspector + Groovy Binding
      │
      ▼
fields / preconditions / predicates
      │
      ▼
InspectionResult / PolicyBreaches
      │
      ▼
FieldEnricher / Source location / DrawIO enrichment
```

El valor principal es la **normalización previa a la policy**.

## 2. Evidencia observada en código/tests

### JSON

`JsonInputModelParser`:

- convierte JSON a `Map`;
- si detecta `kind` + `apiVersion`, conserva semántica de recurso;
- si no, crea `JsonResource` genérico.

### YAML

`YamlInputModelParser`:

- admite documento único o lista;
- convierte recursos conocidos a modelos concretos;
- el resto a `GenericResource`;
- soporta múltiples recursos.

### CSV

`CSVInputModelParser`:

- transforma el contenido a estructura tabular;
- lo envuelve como `GenericResource.spec`.

### DrawIO

El parser convierte XML/DrawIO a un `DrawioDiagram` estructurado y luego se evalúa exactamente mediante la misma maquinaria de rules.

### Tests multiformato

`AdmissionControllerSpec` demuestra las mismas policies Kubernetes aplicadas a YAML y JSON. Esto confirma que **el parser es el borde y la policy opera sobre estructura, no formato**.

### Librería base

`MapUtils.findDeep()` aporta navegación profunda sobre mapas/listas, índices y filtros. `ObjectCreator` puede proyectar mapas a clases cuando existe un modelo.

### De dónde sale `obj.foo.bar`

No de `MapUtils`. Sale de Groovy: sus maps soportan acceso estilo propiedad y `GroovyShell` evalúa strings contra un `Binding`.

Ésta es la capacidad que debemos recuperar con Kotlin de manera controlada.

## 3. Qué conservar

1. Resource parser registry.
2. Modelo genérico para datos sin clase conocida.
3. Policies independientes del formato físico.
4. `match`/applicability separado de validation.
5. preconditions.
6. breaches explícitos.
7. localización de fields.
8. enriquecimiento para UI/agentes.
9. múltiples recursos en una entrada.
10. capacidad de usar modelos concretos como optimización, no requisito.

## 4. Qué eliminar/replantear

### `GroovyShell.evaluate(String)`

Reemplazar por AST/IR tipado.

### Binding mutable

Reemplazar por `EvaluationEnv` inmutable pasado explícitamente.

### `passed + isExecuted + semanticError`

Reemplazar por ADT cerrado.

### `StructuredResource` Kubernetes-like obligatorio

No exigir `apiVersion/kind/spec/status` a cualquier dataset.

### Field location reconstruida después

Preservar location durante parsing mediante `SourceMap<NodeId, SourceAnchor>`.

### `ValidationsMutation` por merge implícito

Reemplazar por composición de layers y reglas de supersession explícitas.

### comparación de cantidades como strings

Introducir funciones puras tipadas (`quantity`, `semver`, `duration`, `cidr`, etc.).

## 5. Mapeo referencia → nuevo producto

| Referencia | Nuevo diseño |
|---|---|
| `StructuredResource` | `ResourceDocument` |
| `GenericResource.spec` | `ValueTree.root` |
| `IResourceParser` | `ResourceDecoder` |
| `CollectionResources` | `Sequence<ResourceDocument>` |
| `MapUtils.findDeep` | `Selector/DocumentPath` |
| Groovy `map.foo` | FIR symbolic property |
| `Binding` | `EvaluationEnv` immutable |
| `fields` | symbolic selectors |
| `predicate String` | `Expr<Boolean>` |
| `precondition String` | `ApplicabilityExpr` |
| `PolicyBreaches` | `PolicyViolation` |
| `FieldLocation` | `SourceAnchor` |
| `FieldEnricher` | source-aware decode + optional enrichers |
| `AdmissionController` | `PolicyEvaluator` + `EnforcementInterpreter` |
| `ValidationsMutation` | `PolicyLayerComposer` |

## 6. Conclusión

La reescritura correcta no es “pasar Groovy a Kotlin”. Es conservar la abstracción fuerte que ya existía —**normalizar cualquier recurso a un árbol recorrible y aplicar políticas sin conocer el formato**— y reemplazar la parte dinámica por una arquitectura Kotlin funcional, compilable y verificable.
