package dev.usbharu.graphmd.cli

internal fun runCliMain(args: Array<String>) {
    val result = GraphMdCli().run(args.toList())
    if (result.stdout.isNotEmpty()) writeStandardOutput(result.stdout)
    if (result.stderr.isNotEmpty()) writeStandardError(result.stderr)
    if (result.exitCode != 0) terminateProcess(result.exitCode)
}
