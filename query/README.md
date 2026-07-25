# GraphMD query engine

The `query` module is a Kotlin Multiplatform search engine over the normalized
output of `core`. It deliberately keeps four layers separate:

```text
GraphCompilationResult
  -> QueryableGraph
  -> SearchIndex
  -> GraphSearchEngine
```

`QueryableGraphBuilder` expands nodes and relations into property, relation,
and text assertions. Each assertion carries an `IntervalSet`. Query execution
also carries that set in every `QueryBinding`, so an `AND` or relation traversal
only succeeds when all matched assertions are true during at least one shared
interval.

Pass the original `SourceDocument` list when building if normal Markdown body
text should be searchable. Without it, normalized property strings and relation
labels are still indexed.

## Building and searching

```kotlin
val compilation = GraphCompiler().compileSources(sources)
val engine = GraphSearchEngine.build(compilation, sources)

val query = GraphQuery(
    root = NodePattern(
        typeId = NodeTypeId("Person"),
        includeDerivedTypes = true,
    ),
    temporalWindow = TemporalWindow.At(
        timelineId = TimelineId("TimelineA"),
        instant = 150.0,
    ),
    expression = GraphQueryExpression.And(
        listOf(
            GraphQueryExpression.Property(
                PropertyPredicate(
                    path = PropertyPath("age"),
                    operator = ValueOperator.GREATER_THAN_OR_EQUALS,
                    value = NumberValue(15.0),
                ),
            ),
            GraphQueryExpression.Relation(
                RelationPattern(
                    typeId = RelationTypeId("friendOf"),
                    target = NodePattern(id = NodeId("bob")),
                    targetVariable = VariableId("friend"),
                ),
            ),
        ),
    ),
)

val result: QueryResult = engine.search(query)
```

Expressions support `And`, `Or`, time-aware `Not`, property comparisons,
relation patterns with nested target expressions, and text predicates.
`engine.scan(query)` runs the unindexed reference semantics and is intended for
differential tests.

## Temporal behavior

GraphMD source `validTime.from` and `validTime.to` remain inclusive.
`TemporalWindow.Range` is explicitly half-open, while
`TemporalWindow.ClosedRange` has inclusive endpoints. Timeline mappings are
normalized onto a canonical axis before joins.

The internal temporal operators have explicit direction:

- `OVERLAPS`
- `ASSERTION_CONTAINS_QUERY`
- `QUERY_CONTAINS_ASSERTION`
- `AT`

Every relation traversal intersects the current binding, relation, target node,
target property, and text assertion times. Empty intersections are discarded.

## Static distribution

```kotlin
val bundle = engine.exportStatic(
    SearchIndexFormatOptions(
        compilerVersion = "0.1.0",
        maxEntriesPerShard = 1_000,
    ),
)

bundle.files().forEach { (relativePath, json) ->
    // Write relativePath and json using the host platform's file API.
}

val loaded = GraphSearchEngine.loadStatic(bundle)
```

The manifest records the format, compiler and analyzer versions, checksum, and
all shard names. The bundle stores logical assertions and the physical property,
relation, interval, and full-text posting lists. Loading validates the format,
analyzer version, referenced shards, checksum, and assertion references before
the index can execute.
