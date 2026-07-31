plugins {
    alias(libs.plugins.kotlinMultiplatform)
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
