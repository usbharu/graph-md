# graph-md

This project uses [Gradle](https://gradle.org/).
To build and run the application, use the *Gradle* tool window by clicking the Gradle icon in the right-hand toolbar,
or run it directly from the terminal:

* Run `./gradlew run` to build and run the application.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`).
This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

The Gradle build consists of the `core`, `query`, `lsp`, and `cli` subprojects.

## Graph search engine

The `query` Kotlin Multiplatform module turns `GraphCompilationResult` into an
assertion-based searchable IR, keeps valid-time intervals on every binding, and
provides both a scan reference executor and a physical indexed executor.

```kotlin
val compilation = GraphCompiler().compileSources(sources)
val engine = GraphSearchEngine.build(compilation, sources)

val result = engine.search(
    GraphQuery(
        root = NodePattern(typeId = NodeTypeId("Person")),
        temporalWindow = TemporalWindow.At(TimelineId("CommonEra"), 150.0),
        expression = GraphQueryExpression.Property(
            PropertyPredicate(
                PropertyPath("age"),
                ValueOperator.GREATER_THAN_OR_EQUALS,
                NumberValue(15.0),
            ),
        ),
    ),
)
```

The index supports typed property lookup, relation traversal, mapped timelines,
Japanese N-grams, identifier terms, BM25 scoring, deterministic JSON sharding,
and checksum-verified static loading. See [the query module guide](query/README.md)
for its query and distribution APIs.

## GraphMD CLI

The `graphmd` CLI is implemented in the `cli` multiplatform module.

```sh
./gradlew :cli:run --args="list ./documents --kind node --type Person"
./gradlew :cli:run --args="show alice ./documents --json"
./gradlew :cli:run --args="links alice ./documents --direction incoming"
./gradlew :cli:run --args="show alice ./documents --valid-time 'CommonEra(from=10,to=20)'"
./gradlew :cli:run --args="lint ./documents --strict"
./gradlew :cli:run --args="stats ./documents"
./gradlew :cli:run --args='search "MATCH (person:Person) WHERE person.age >= $minimumAge RETURN person" ./documents --param minimumAge=18 --json'
./gradlew :cli:run --args="search --query-file queries/people.gmql ./documents --param minimumAge=18"
./gradlew :cli:run --args="demo ./benchmark-data --count 1000 --seed 42"
```

`search` executes GMQL against the documents found at the supplied paths.
Inline queries and `--query-file` are supported. Repeat `--param NAME=VALUE`
for prepared-query parameters; `null`, booleans, integers, decimals, and
quoted JSON strings are inferred, while other values are strings. Results are
tab-separated by default, or JSON row objects with `--json`.

Release archives are built with `jvmReleaseJar`, `jsReleaseArchive`,
`macosArm64ReleaseArchive`, `macosX64ReleaseArchive`,
`linuxX64ReleaseArchive`, and `mingwX64ReleaseArchive`. Tag pushes matching
`vMAJOR.MINOR.PATCH` publish these archives and the VSIX to one GitHub Release.

When no path is supplied, the CLI searches the current directory recursively.
Discovered `.md` files are treated as GraphMD only when their first line is
`---`; explicitly named files are always validated.

`--valid-time` is available on every operation. It accepts GraphMD ValidTime
syntax such as `CommonEra`, `CommonEra(from=10)`, or
`CommonEra(from=10,to=20)`, and only includes overlapping assertions on that
Timeline or the ancestor Timelines it `extends`. A Timeline `mapping` alone
does not make assertions visible. When a Property or Link matches but its
Document does not, the CLI returns an `assertion-only` entity containing only
its ID and the matching assertions. A matching Link also exposes out-of-range
endpoints as `assertion-only` ID references.

Only `lint` emits validation warnings. Other operations keep warnings out of
stderr while still reporting validation errors; `stats` retains the warning
count as an aggregate.

`demo DIR --count N` generates a compact, randomized GraphMD dataset containing
Nodes, Media, links, properties, NodeType and RelType hierarchies, and Timeline
hierarchies. The output directory must be new or empty. Counts below eight are
expanded to the minimum complete eight-document dataset. Omit `--seed` for a
new dataset on each run, or provide an integer seed to reproduce the exact same
files and relationships. The seed used is always included in command output.
Documents are generated and written one at a time, so memory use does not grow
with the requested file count.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).
