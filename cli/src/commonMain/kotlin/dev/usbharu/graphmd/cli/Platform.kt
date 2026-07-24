package dev.usbharu.graphmd.cli

internal expect fun writeStandardOutput(text: String)
internal expect fun writeStandardError(text: String)
internal expect fun terminateProcess(code: Int): Nothing
