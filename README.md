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

The Gradle build consists of the `core`, `lsp`, and `cli` subprojects.

## GraphMD CLI

The `graphmd` CLI is implemented in the `cli` multiplatform module.

```sh
./gradlew :cli:run --args="list ./documents --kind node --type Person"
./gradlew :cli:run --args="show alice ./documents --json"
./gradlew :cli:run --args="links alice ./documents --direction incoming"
./gradlew :cli:run --args="show alice ./documents --valid-time 'CommonEra(from=10,to=20)'"
./gradlew :cli:run --args="lint ./documents --strict"
./gradlew :cli:run --args="stats ./documents"
```

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

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).
