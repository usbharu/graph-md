package dev.usbharu.graphmd.cli

private external object process {
    fun exit(code: Int): Nothing
}

internal actual fun writeStandardOutput(text: String) {
    kotlin.io.print(text)
}

internal actual fun writeStandardError(text: String) {
    js("process.stderr.write(text)")
}

internal actual fun terminateProcess(code: Int): Nothing = process.exit(code)
