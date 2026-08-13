plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

fun resolveDevelopmentExecutable(name: String): File? {
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val executableNames = if (isWindows) {
        listOf("$name.cmd", "$name.exe", name)
    } else {
        listOf(name)
    }
    val propertyName = "${name}Executable"
    val environmentName = "${name.uppercase()}_EXECUTABLE"
    val explicitCandidates = listOfNotNull(
        providers.gradleProperty(propertyName).orNull,
        providers.environmentVariable(environmentName).orNull,
    )
    val pathCandidates = providers.environmentVariable("PATH").orNull
        .orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> executableNames.map { File(directory, it).path } }
    val userHome = providers.systemProperty("user.home").orNull
    val managedCandidates = if (userHome == null) {
        emptyList()
    } else {
        listOf(
            "$userHome/.local/share/pnpm",
            "$userHome/.local/share/mise/shims",
            "$userHome/.local/share/mise/installs/$name/latest",
            "$userHome/.asdf/shims",
            "$userHome/.volta/bin",
        ).flatMap { directory -> executableNames.map { "$directory/$it" } }
    }
    return (explicitCandidates + pathCandidates + managedCandidates)
        .asSequence()
        .map(::File)
        .firstOrNull { it.isFile && (isWindows || it.canExecute()) }
}

val pnpmExecutable = resolveDevelopmentExecutable("pnpm")
val nodeExecutable = resolveDevelopmentExecutable("node")
val developmentToolPath = buildList {
    pnpmExecutable?.parentFile?.absolutePath?.let(::add)
    nodeExecutable?.parentFile?.absolutePath?.let(::add)
    providers.environmentVariable("PATH").orNull?.let(::add)
}.distinct().joinToString(File.pathSeparator)

fun Exec.configureDevelopmentTools() {
    if (developmentToolPath.isNotEmpty()) {
        environment("PATH", developmentToolPath)
    }
}

version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()
val cliReleaseVersion = version.toString()
val generatedVersionDirectory = layout.buildDirectory.dir("generated/cliVersion")
val generatedVersionSource = resources.text.fromString(
    """
    package dev.usbharu.graphmd.cli

    internal const val cliVersion = "$cliReleaseVersion"
    """.trimIndent() + "\n",
)

val siteTemplateDirectory = rootProject.layout.projectDirectory.dir("site-template")
val stagedSiteTemplateDirectory = layout.buildDirectory.dir("generated/siteTemplateFiles")
val embeddedWebRuntimeDirectory = siteTemplateDirectory.dir("runtime-encoded")
val generatedSiteTemplateDirectory = layout.buildDirectory.dir("generated/siteTemplate")
val generatedSiteTemplateSource = generatedSiteTemplateDirectory.map {
    it.file("dev/usbharu/graphmd/cli/GeneratedSiteTemplate.kt")
}

val syncMarkdownCoreVendor by tasks.registering(Sync::class) {
    dependsOn(":core:jsNodeProductionLibraryDistribution")
    from(rootProject.layout.projectDirectory.dir("core/build/dist/js/productionLibrary")) {
        include("graph-md-core.js", "kotlin-kotlin-stdlib.js", "kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js")
    }
    from(resources.text.fromString("{\n  \"private\": true,\n  \"type\": \"commonjs\"\n}\n")) {
        rename { "package.json" }
    }
    into(rootProject.layout.projectDirectory.dir("markdown-it-graphmd/vendor"))
}

val bundleMarkdownItGraphMd by tasks.registering(Exec::class) {
    dependsOn(syncMarkdownCoreVendor)
    workingDir(rootProject.projectDir)
    // This bundle is compiled into the multiplatform CLI as generated source.
    // Keeping development sourcemaps and whitespace here makes every Kotlin
    // target parse more than a megabyte of string literals during `check`.
    configureDevelopmentTools()
    commandLine(pnpmExecutable?.absolutePath ?: "pnpm", "--dir", "markdown-it-graphmd", "exec", "tsup", "--minify", "--sourcemap=false")
    inputs.files(fileTree(rootProject.file("markdown-it-graphmd/src")) { include("**/*.ts") })
    outputs.file(rootProject.file("markdown-it-graphmd/dist/index.js"))
}

val bundledQueryRuntime = layout.buildDirectory.file("webRuntime/graph-md-query-runtime.js")
val bundleQueryWebRuntime by tasks.registering(Exec::class) {
    dependsOn(":query:jsProductionLibraryCompileSync")
    workingDir(rootProject.projectDir)
    val queryEntry = rootProject.file("query/build/compileSync/js/main/productionLibrary/kotlin/graph-md-query.js")
    inputs.file(queryEntry)
    inputs.files(
        rootProject.file("query/build/compileSync/js/main/productionLibrary/kotlin/graph-md-core.js"),
        rootProject.file("query/build/compileSync/js/main/productionLibrary/kotlin/kotlin-kotlin-stdlib.js"),
    )
    outputs.file(bundledQueryRuntime)
    configureDevelopmentTools()
    commandLine(
        pnpmExecutable?.absolutePath ?: "pnpm", "exec", "esbuild", queryEntry.absolutePath,
        "--bundle", "--platform=browser", "--format=iife", "--global-name=GraphMdQueryRuntime",
        "--minify",
        "--outfile=${bundledQueryRuntime.get().asFile.absolutePath}",
    )
}

val updateEmbeddedWebRuntime by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Rebuilds the checked-in browser runtimes (requires pnpm and Node.js)."
    dependsOn(bundleQueryWebRuntime, bundleMarkdownItGraphMd)
    val markdownBundle = rootProject.layout.projectDirectory.file("markdown-it-graphmd/dist/index.js")
    inputs.files(bundledQueryRuntime, markdownBundle)
    outputs.files(
        embeddedWebRuntimeDirectory.file("graph-md-query-runtime.js.gz.b64"),
        embeddedWebRuntimeDirectory.file("markdown-it-graphmd.js.gz.b64"),
    )
    configureDevelopmentTools()
    commandLine(
        nodeExecutable?.absolutePath ?: "node", rootProject.file("scripts/embed-web-runtime.mjs").absolutePath,
        embeddedWebRuntimeDirectory.asFile.absolutePath,
        bundledQueryRuntime.get().asFile.absolutePath,
        markdownBundle.asFile.absolutePath,
    )
}

val siteTemplateFiles = fileTree(siteTemplateDirectory) {
    exclude(
        "node_modules/**", "dist/**", ".astro/**", "public/runtime/**", "public/search-index/**",
        "src/vendor/**", "vendor/**", "tests/**",
    )
}

val stageSiteTemplate by tasks.registering(Sync::class) {
    dependsOn(":astro:jsNodeProductionLibraryDistribution")
    from(siteTemplateFiles)
    filesMatching(listOf("package.json", "pnpm-lock.yaml")) {
        filter { line ->
            line.replace(
                "../astro/build/dist/js/productionLibrary",
                "./vendor/graph-md-astro",
            )
        }
    }
    from(rootProject.layout.projectDirectory.dir("astro/build/dist/js/productionLibrary")) {
        into("vendor/graph-md-astro")
    }
    into(stagedSiteTemplateDirectory)
}

val generateSiteTemplate by tasks.registering(GenerateSiteTemplateTask::class) {
    dependsOn(stageSiteTemplate)
    sourceRoot.set(stagedSiteTemplateDirectory)
    templateFiles.from(stagedSiteTemplateDirectory.map { it.asFileTree })
    outputFile.set(generatedSiteTemplateSource)
}

val generateCliVersion by tasks.registering(Sync::class) {
    inputs.property("version", cliReleaseVersion)
    from(generatedVersionSource) {
        rename { "GeneratedVersion.kt" }
    }
    into(generatedVersionDirectory.map { it.dir("dev/usbharu/graphmd/cli") })
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    js(IR) {
        nodejs()
        binaries.executable()
    }
    macosArm64 {
        binaries.executable {
            baseName = "graphmd"
            entryPoint = "dev.usbharu.graphmd.cli.main"
        }
    }
    macosX64 {
        binaries.executable {
            baseName = "graphmd"
            entryPoint = "dev.usbharu.graphmd.cli.main"
        }
    }
    linuxX64 {
        binaries.executable {
            baseName = "graphmd"
            entryPoint = "dev.usbharu.graphmd.cli.main"
        }
    }
    mingwX64 {
        binaries.executable {
            baseName = "graphmd"
            entryPoint = "dev.usbharu.graphmd.cli.main"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateCliVersion)
            kotlin.srcDir(files(generatedSiteTemplateDirectory).builtBy(generateSiteTemplate))
            dependencies {
                implementation(project(":core"))
                implementation(project(":query"))
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the GraphMD CLI on the JVM."
    dependsOn("jvmMainClasses")
    mainClass.set("dev.usbharu.graphmd.cli.MainKt")
    classpath(
        kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles,
        kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs,
    )
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.named<Test>("jvmTest") {
    val embeddedQueryRuntime = embeddedWebRuntimeDirectory.file("graph-md-query-runtime.js.gz.b64")
    inputs.file(embeddedQueryRuntime)
    systemProperty(
        "graphmd.embeddedQueryRuntime",
        embeddedQueryRuntime.asFile.absolutePath,
    )
}

tasks.register<Jar>("jvmReleaseJar") {
    group = "distribution"
    description = "Builds a self-contained JVM CLI jar."
    dependsOn("jvmMainClasses")
    archiveFileName.set("graphmd-jvm-${project.version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.usbharu.graphmd.cli.MainKt"
    }
    from(jvmMainCompilation.output.allOutputs)
    from(jvmMainCompilation.runtimeDependencyFiles!!.map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register<Zip>("jsReleaseArchive") {
    group = "distribution"
    description = "Builds the Node.js CLI archive."
    dependsOn("jsProductionExecutableCompileSync")
    archiveFileName.set("graphmd-node-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin"))
}

fun registerNativeTar(
    taskName: String,
    targetName: String,
    platformName: String,
) {
    tasks.register<Tar>(taskName) {
        group = "distribution"
        dependsOn("linkReleaseExecutable$targetName")
        compression = Compression.GZIP
        archiveFileName.set("graphmd-$platformName-${project.version}.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        filePermissions {
            unix("rwxr-xr-x")
        }
        from(layout.buildDirectory.file("bin/${targetName.replaceFirstChar(Char::lowercase)}/releaseExecutable/graphmd.kexe")) {
            rename { "graphmd" }
        }
    }
}

registerNativeTar("macosArm64ReleaseArchive", "MacosArm64", "macos-arm64")
registerNativeTar("macosX64ReleaseArchive", "MacosX64", "macos-x64")
registerNativeTar("linuxX64ReleaseArchive", "LinuxX64", "linux-x64")

tasks.register<Zip>("mingwX64ReleaseArchive") {
    group = "distribution"
    dependsOn("linkReleaseExecutableMingwX64")
    archiveFileName.set("graphmd-windows-x64-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.file("bin/mingwX64/releaseExecutable/graphmd.exe"))
}
