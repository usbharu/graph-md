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

Named body-block fence lines are omitted from searchable text and split the
surrounding fragments. A fragment inherits the innermost block's `validTime`;
a names-only block inherits its parent block or node time. This is resolved
while rebuilding the index from `SourceDocument`, without changing the static
bundle schema.

Dynamic `embed:query` and `embed:back-link` contents are generated cache data,
so they are omitted entirely from body text, property, and relation assertions.
Embed execution uses a 100-row result limit.

## Building and searching

```kotlin
val compilation = GraphCompiler().compileSources(sources)
val engine = GraphSearchEngine.build(compilation, sources)

val query = GraphQuery(
    root = NodePattern(
        typeId = NodeTypeId("Person"),
        includeDerivedTypes = true,
    ),
    temporalWindow = TemporalWindow.At(TimelineId("CommonEra"), TemporalCoordinate.CalendarDate(2026, 1, 1)),
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
require `VALID ON <timeline>`. Bounds may be numbers or quoted date/timecode
strings and are parsed using that Timeline's coordinate.

Recurring `calendar-pattern` values additionally require a finite, half-open
calendar expansion window. The bounds are complete dates interpreted with the
Timeline's calendar and numbering, independently of its custom value format:

```gmql
MATCH (n:Person)
VALID ON Birthday AT "08-08"
WITHIN ["2000-01-01", "2031-01-01")
RETURN n
```

`VALID ON Birthday ANYTIME WITHIN [...]` is available when a query needs to join recurring
assertions without selecting a narrower temporal value. Omitting `WITHIN` when
recurring validity participates in evaluation returns `GMQL4004`.

## Temporal behavior

GraphMD source `validTime.from` and `validTime.to` remain inclusive.
`TemporalWindow.Range` is explicitly half-open, while
`TemporalWindow.ClosedRange` has inclusive endpoints. `sameAxisAs` Timeline
representations share an Axis and search assertion scope. Cross-Axis search
uses only unique, exact, order-preserving `mapsTo` paths; approximate,
ambiguous, and non-monotonic paths remain available only to conversion APIs.

The internal temporal operators have explicit direction:

- `OVERLAPS`
- `ASSERTION_CONTAINS_QUERY`
- `QUERY_CONTAINS_ASSERTION`
- `AT`

Every relation traversal intersects the current binding, relation, target node,
target property, and text assertion times. Empty intersections are discarded.
Body text assertions use the innermost named block's resolved time, so the same
full-text term can match or fail under `VALID ON` depending on its source block.

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
The current bundle format is v4 and serializes temporal rationals as
`{numerator, denominator}`. Older bundle versions are rejected and must be regenerated.
