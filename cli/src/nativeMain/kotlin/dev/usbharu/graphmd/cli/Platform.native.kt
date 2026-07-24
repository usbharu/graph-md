package dev.usbharu.graphmd.cli

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fputs
import platform.posix.stderr
import kotlin.system.exitProcess

internal actual fun writeStandardOutput(text: String) {
    kotlin.io.print(text)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeStandardError(text: String) {
    fputs(text, stderr)
}

internal actual fun terminateProcess(code: Int): Nothing = exitProcess(code)
