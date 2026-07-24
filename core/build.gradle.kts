plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kover)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    js(IR) {
        nodejs()
        generateTypeScriptDefinitions()
        binaries.library()
    }
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                classes("dev.usbharu.graphmd.core.model.*")
            }
        }
        total {
            xml {
                onCheck = false
            }
            html {
                onCheck = false
            }
        }
    }
}
