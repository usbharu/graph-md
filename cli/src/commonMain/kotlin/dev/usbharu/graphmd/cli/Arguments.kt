package dev.usbharu.graphmd.cli

internal enum class CliKind(val wireName: String, val order: Int) {
    Node("node", 0),
    Media("media", 1),
    Link("link", 2),
    NodeType("node-type", 3),
    RelType("rel-type", 4),
    Timeline("timeline", 5);

    companion object {
        fun parse(value: String): CliKind? {
            val normalized = value.lowercase().replace("_", "-")
            return entries.firstOrNull { it.wireName == normalized } ?: when (normalized) {
                "nodetype" -> NodeType
                "reltype" -> RelType
                else -> null
            }
        }
    }
}

internal enum class LinkDirection {
    Incoming,
    Outgoing,
    Both;

    companion object {
        fun parse(value: String): LinkDirection? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

internal data class ValidTimeFilter(
    val timeline: String,
    val from: Double?,
    val to: Double?,
)

internal sealed interface CliCommand {
    val paths: List<String>

    data class ListItems(
        override val paths: List<String>,
        val kinds: Set<CliKind>,
        val types: Set<String>,
        val includeDerived: Boolean,
        val validTime: ValidTimeFilter?,
    ) : CliCommand

    data class Show(
        val id: String,
        override val paths: List<String>,
        val kinds: Set<CliKind>,
        val validTime: ValidTimeFilter?,
    ) : CliCommand

    data class Props(
        val id: String,
        override val paths: List<String>,
        val kinds: Set<CliKind>,
        val validTime: ValidTimeFilter?,
    ) : CliCommand

    data class Links(
        val id: String,
        override val paths: List<String>,
        val kinds: Set<CliKind>,
        val direction: LinkDirection,
        val types: Set<String>,
        val includeDerived: Boolean,
        val validTime: ValidTimeFilter?,
    ) : CliCommand

    data class Lint(
        override val paths: List<String>,
        val strict: Boolean,
        val validTime: ValidTimeFilter?,
    ) : CliCommand

    data class Stats(
        override val paths: List<String>,
        val kinds: Set<CliKind>,
        val types: Set<String>,
        val includeDerived: Boolean,
        val validTime: ValidTimeFilter?,
    ) : CliCommand

    data class Search(
        val query: String?,
        val queryFile: String?,
        override val paths: List<String>,
        val parameters: Map<String, String>,
    ) : CliCommand

    data class Embed(
        override val paths: List<String>,
    ) : CliCommand

    data class Demo(
        val outputDirectory: String,
        val requestedCount: Int,
        val seed: Int?,
        override val paths: List<String> = emptyList(),
    ) : CliCommand
}

internal sealed interface ParseResult {
    data class Run(val command: CliCommand, val json: Boolean) : ParseResult
    data class Print(val text: String) : ParseResult
    data class Error(val message: String, val exitCode: Int = 2) : ParseResult
}

internal object CliArguments {
    fun parse(arguments: List<String>): ParseResult {
        if (arguments.isEmpty()) return ParseResult.Print(rootHelp())
        val json = "--json" in arguments
        val help = "--help" in arguments || "-h" in arguments
        if ("--version" in arguments || "-V" in arguments) {
            return ParseResult.Print("graphmd $cliVersion\n")
        }
        val remaining = arguments.filterNot { it == "--json" || it == "--help" || it == "-h" }
        if (remaining.isEmpty()) return ParseResult.Print(rootHelp())
        val operation = remaining.first()
        if (help) return ParseResult.Print(commandHelp(operation))
        val tokens = remaining.drop(1)
        return try {
            ParseResult.Run(parseCommand(operation, tokens), json)
        } catch (exception: CliUsageException) {
            ParseResult.Error(exception.message ?: "Invalid arguments", if (operation == "demo") 1 else 2)
        }
    }

    private fun parseCommand(operation: String, tokens: List<String>): CliCommand {
        val parsed = parseTokens(tokens)
        return when (operation) {
            "list" -> {
                parsed.reject(setOf("kind", "type", "include-derived", "valid-time"))
                CliCommand.ListItems(
                    parsed.positionals,
                    parsed.kinds(),
                    parsed.types(),
                    parsed.flag("include-derived"),
                    parsed.validTime(),
                )
            }
            "show" -> {
                parsed.reject(setOf("kind", "valid-time"))
                val (id, paths) = parsed.idAndPaths("show")
                val kinds = parsed.kinds()
                if (CliKind.Link in kinds) usage("show does not support --kind link")
                CliCommand.Show(id, paths, kinds, parsed.validTime())
            }
            "props" -> {
                parsed.reject(setOf("kind", "valid-time"))
                val (id, paths) = parsed.idAndPaths("props")
                val kinds = parsed.kinds()
                if (kinds.any { it !in setOf(CliKind.Node, CliKind.Media) }) {
                    usage("props only supports --kind node or --kind media")
                }
                CliCommand.Props(id, paths, kinds, parsed.validTime())
            }
            "links" -> {
                parsed.reject(setOf("kind", "type", "include-derived", "direction", "valid-time"))
                val (id, paths) = parsed.idAndPaths("links")
                val kinds = parsed.kinds()
                if (kinds.any { it !in setOf(CliKind.Node, CliKind.Media) }) {
                    usage("links only supports --kind node or --kind media")
                }
                val directionValues = parsed.values["direction"].orEmpty()
                if (directionValues.size > 1) usage("--direction may only be specified once")
                val direction = directionValues.singleOrNull()?.let {
                    LinkDirection.parse(it) ?: usage("Unknown direction: $it")
                } ?: LinkDirection.Both
                CliCommand.Links(
                    id,
                    paths,
                    kinds,
                    direction,
                    parsed.types(),
                    parsed.flag("include-derived"),
                    parsed.validTime(),
                )
            }
            "lint" -> {
                parsed.reject(setOf("strict", "valid-time"))
                CliCommand.Lint(parsed.positionals, parsed.flag("strict"), parsed.validTime())
            }
            "stats" -> {
                parsed.reject(setOf("kind", "type", "include-derived", "valid-time"))
                CliCommand.Stats(
                    parsed.positionals,
                    parsed.kinds(),
                    parsed.types(),
                    parsed.flag("include-derived"),
                    parsed.validTime(),
                )
            }
            "search" -> {
                parsed.reject(setOf("query-file", "param"))
                val queryFiles = parsed.values["query-file"].orEmpty()
                if (queryFiles.size > 1) usage("--query-file may only be specified once")
                val queryFile = queryFiles.singleOrNull()
                val query = if (queryFile == null) {
                    parsed.positionals.firstOrNull() ?: usage("search requires a QUERY or --query-file")
                } else {
                    null
                }
                val paths = if (queryFile == null) parsed.positionals.drop(1) else parsed.positionals
                val parameters = linkedMapOf<String, String>()
                parsed.values["param"].orEmpty().forEach { encoded ->
                    val name = encoded.substringBefore("=", missingDelimiterValue = "")
                    if (name.isEmpty() || "=" !in encoded || !PARAMETER_NAME.matches(name)) {
                        usage("--param must be NAME=VALUE")
                    }
                    if (name in parameters) usage("Duplicate parameter: $name")
                    parameters[name] = encoded.substringAfter("=")
                }
                CliCommand.Search(query, queryFile, paths, parameters)
            }
            "embed" -> {
                parsed.reject(emptySet())
                CliCommand.Embed(parsed.positionals)
            }
            "demo" -> {
                parsed.reject(setOf("count", "seed"))
                if (parsed.positionals.size != 1) usage("demo requires exactly one output directory")
                val count = checkNotNull(parsed.singleValue("count", required = true))
                    .toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: usage("--count must be a positive integer")
                val seed = parsed.singleValue("seed", required = false)?.toIntOrNull()
                    ?: if ("seed" in parsed.values) usage("--seed must be an integer") else null
                CliCommand.Demo(parsed.positionals.single(), count, seed)
            }
            else -> usage("Unknown operation: $operation")
        }
    }

    private fun parseTokens(tokens: List<String>): ParsedTokens {
        val values = linkedMapOf<String, MutableList<String>>()
        val flags = linkedSetOf<String>()
        val positionals = mutableListOf<String>()
        var positionalOnly = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (positionalOnly) {
                positionals += token
                index++
                continue
            }
            if (token == "--") {
                positionalOnly = true
                index++
                continue
            }
            if (!token.startsWith("--")) {
                positionals += token
                index++
                continue
            }
            val name = token.substringAfter("--").substringBefore("=")
            val inlineValue = token.substringAfter("=", missingDelimiterValue = "").takeIf { "=" in token }
            when (name) {
                "include-derived", "strict" -> {
                    if (inlineValue != null) usage("--$name does not take a value")
                    flags += name
                    index++
                }
                "kind", "type", "direction", "valid-time", "query-file", "param", "count", "seed" -> {
                    val value = inlineValue ?: tokens.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
                        ?: usage("--$name requires a value")
                    values.getOrPut(name) { mutableListOf() } += value
                    index += if (inlineValue == null) 2 else 1
                }
                else -> usage("Unknown option: --$name")
            }
        }
        return ParsedTokens(positionals, values, flags)
    }

    private fun ParsedTokens.kinds(): Set<CliKind> = values["kind"].orEmpty().mapTo(linkedSetOf()) {
        CliKind.parse(it) ?: usage("Unknown kind: $it")
    }

    private fun ParsedTokens.types(): Set<String> = values["type"].orEmpty().toCollection(linkedSetOf())

    private fun ParsedTokens.validTime(): ValidTimeFilter? {
        val specified = values["valid-time"].orEmpty()
        if (specified.size > 1) usage("--valid-time may only be specified once")
        val value = specified.singleOrNull() ?: return null
        val match = VALID_TIME_PATTERN.matchEntire(value.trim())
            ?: usage("--valid-time must be TIMELINE, TIMELINE(from=N), TIMELINE(to=N), or TIMELINE(from=N,to=N)")
        val timeline = match.groupValues[1]
        var from: Double? = null
        var to: Double? = null
        val arguments = match.groupValues[2]
        if (arguments.isNotBlank()) {
            arguments.split(",").forEach { argument ->
                val parts = argument.split("=", limit = 2).map(String::trim)
                if (parts.size != 2 || parts[0] !in setOf("from", "to")) {
                    usage("Invalid --valid-time bound: $argument")
                }
                val timecode = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: usage("Invalid --valid-time timecode: ${parts[1]}")
                when (parts[0]) {
                    "from" -> if (from == null) from = timecode else usage("Duplicate from bound")
                    "to" -> if (to == null) to = timecode else usage("Duplicate to bound")
                }
            }
        }
        if (from != null && to != null && from > to) usage("--valid-time from must not exceed to")
        return ValidTimeFilter(timeline, from, to)
    }

    private fun ParsedTokens.idAndPaths(operation: String): Pair<String, List<String>> {
        val id = positionals.firstOrNull() ?: usage("$operation requires an ID")
        return id to positionals.drop(1)
    }

    private fun ParsedTokens.flag(name: String): Boolean = name in flags

    private fun ParsedTokens.singleValue(name: String, required: Boolean): String? {
        val specified = values[name].orEmpty()
        if (specified.size > 1) usage("--$name may only be specified once")
        if (required && specified.isEmpty()) usage("--$name is required")
        return specified.singleOrNull()
    }

    private fun ParsedTokens.reject(allowed: Set<String>) {
        val supplied = values.keys + flags
        val invalid = supplied.firstOrNull { it !in allowed } ?: return
        usage("--$invalid is not valid for this operation")
    }

    private fun usage(message: String): Nothing = throw CliUsageException(message)

    private fun rootHelp(): String = """
        Usage: graphmd <operation> [options] [paths...]

        Operations:
          list    List graph entities
          show    Show an entity by ID
          props   List all property entries for a Node or Media
          links   List incoming and outgoing links for a Node or Media
          lint    Validate GraphMD documents
          stats   Show graph statistics
          search  Execute a GMQL query
          embed   Materialize dynamic embed blocks as Markdown tables
          demo    Generate random, valid GraphMD demo data

        Global options:
          --json       Emit JSON
          --valid-time VALID_TIME
                       Only include assertions overlapping this ValidTime
          --help, -h   Show help
          --version    Show version
    """.trimIndent() + "\n"

    private fun commandHelp(operation: String): String = when (operation) {
        "list" -> "Usage: graphmd list [paths...] [--kind KIND]... [--type ID]... [--include-derived] [--valid-time VALID_TIME] [--json]\n"
        "show" -> "Usage: graphmd show ID [paths...] [--kind KIND]... [--valid-time VALID_TIME] [--json]\n"
        "props" -> "Usage: graphmd props ID [paths...] [--kind node|media] [--valid-time VALID_TIME] [--json]\n"
        "links" -> "Usage: graphmd links ID [paths...] [--kind node|media] [--direction incoming|outgoing|both] [--type ID]... [--include-derived] [--valid-time VALID_TIME] [--json]\n"
        "lint" -> "Usage: graphmd lint [paths...] [--strict] [--valid-time VALID_TIME] [--json]\n"
        "stats" -> "Usage: graphmd stats [paths...] [--kind KIND]... [--type ID]... [--include-derived] [--valid-time VALID_TIME] [--json]\n"
        "search" -> """
            Usage: graphmd search QUERY [paths...] [--param NAME=VALUE]... [--json]
                   graphmd search --query-file FILE [paths...] [--param NAME=VALUE]... [--json]
        """.trimIndent() + "\n"
        "embed" -> "Usage: graphmd embed [paths...] [--json]\n"
        "demo" -> "Usage: graphmd demo DIR --count N [--seed INT] [--json]\n"
        else -> rootHelp()
    }
}

private data class ParsedTokens(
    val positionals: List<String>,
    val values: Map<String, List<String>>,
    val flags: Set<String>,
)

private class CliUsageException(message: String) : RuntimeException(message)

private val VALID_TIME_PATTERN = Regex("""([A-Za-z_][A-Za-z0-9_.:-]*)(?:\((.*)\))?""")
private val PARAMETER_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
