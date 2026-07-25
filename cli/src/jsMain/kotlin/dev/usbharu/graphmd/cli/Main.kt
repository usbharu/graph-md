package dev.usbharu.graphmd.cli

@JsName("process")
private external object nodeProcess {
    val argv: Array<String>
}

fun main() = runCliMain(nodeProcess.argv.drop(2).toTypedArray())
