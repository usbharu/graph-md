package dev.usbharu.graphmd.cli

import kotlin.system.exitProcess

internal actual fun writeStandardOutput(text: String) {
    kotlin.io.print(text)
}

internal actual fun writeStandardError(text: String) {
    System.err.print(text)
}

internal actual fun terminateProcess(code: Int): Nothing = exitProcess(code)
