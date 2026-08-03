plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":query"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.usbharu.graphmd.lsp.MainKt"
}
