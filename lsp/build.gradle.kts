plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.usbharu.graphmd.lsp.MainKt"
}
