plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    js {
        nodejs()
        generateTypeScriptDefinitions()
        binaries.library()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
                api(project(":query"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
