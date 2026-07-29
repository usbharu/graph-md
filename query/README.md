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

An unscoped `FULLTEXT(link, query)` search also considers the linked target
node's level-one Markdown heading (`TITLE`). The title remains owned by the target node:
`FULLTEXT(link.label, query)` searches only the Link label, and targets without
a Markdown title do not synthesize searchable text from their ID or URL.
GraphMD's `[label](target relationType)` form has a visible Link label; its
parenthesized portion is not Markdown's optional link-title syntax.

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

## GMQL v0.1

GMQL text can be compiled once and executed repeatedly with typed parameters:

```kotlin
val compilation = engine.compileGmql(
    """
    MATCH (person:Person)
    WHERE person.age >= ${'$'}minimumAge
      AND FULLTEXT(person, "勇者")
    VALID ON MainStory AT ${'$'}instant
    RETURN ID(person) AS id, VALIDITY() AS validity, SCORE() AS score
    ORDER BY score DESC, id ASC
    """.trimIndent(),
    mapOf(
        "minimumAge" to GmqlType.Integer,
        "instant" to GmqlType.Decimal,
    ),
)
val compiled = requireNotNull(compilation.query) { compilation.diagnostics.joinToString() }
val result = engine.executeGmql(
    compiled,
    mapOf(
        "minimumAge" to GmqlValue.IntegerValue(15),
        "instant" to GmqlValue.DecimalValue(150.0),
    ),
)
```

`queryGmql` is the convenience form and derives parameter types from the
supplied values. Syntax, name, type, temporal and execution-limit failures are
returned as `GMQL1xxx` through `GMQL5xxx` diagnostics.

GraphMD `string` and `text` remain distinct. A `string` is a scalar. A `text`
is a structured, temporal member map: compare `person.biography.default`, or
pass `person.biography` to `FULLTEXT` to search all of its members.

`VALID ANYTIME` may omit a timeline. `AT`, `OVERLAPS`, `CONTAINS`, and `DURING`
require `VALID ON <timeline>` so numeric bounds are never compared across
timeline domains implicitly.

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
