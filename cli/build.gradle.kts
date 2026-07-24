plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

version = "0.1.0"

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
            dependencies {
                implementation(project(":core"))
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
