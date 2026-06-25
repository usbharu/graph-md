plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kover)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

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
            includes {
                classes(
                    "dev.usbharu.graphmd.core.GraphCompiler",
                    "dev.usbharu.graphmd.core.BodySyntaxExtractor",
                    "dev.usbharu.graphmd.core.BodySyntaxExtraction",
                    "dev.usbharu.graphmd.core.InlinePropsParser",
                    "dev.usbharu.graphmd.core.InlinePropsParseException",
                )
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
