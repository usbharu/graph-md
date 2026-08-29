package dev.usbharu.graphmd.query.embed

import dev.usbharu.graphmd.core.model.EmbedDirective
import dev.usbharu.graphmd.query.GraphSearchEngine
import dev.usbharu.graphmd.query.gmql.*
import dev.usbharu.graphmd.query.model.IntervalBoundary
import dev.usbharu.graphmd.query.model.IntervalSet

const val DEFAULT_EMBED_ROW_LIMIT: Int = 100

data class EmbedColumn(val name: String, val type: String)

data class EmbedCell(
    val text: String,
    val targetId: String? = null,
)

data class EmbedRow(val cells: List<EmbedCell>)

data class EmbedTable(
    val columns: List<EmbedColumn>,
    val rows: List<EmbedRow>,
)

data class EmbedDiagnostic(
    val code: String,
    val message: String,
)

data class EmbedRenderResult(
    val table: EmbedTable? = null,
    val diagnostics: List<EmbedDiagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = table != null && diagnostics.isEmpty()
}

class EmbedEngine(
    private val searchEngine: GraphSearchEngine,
    private val maxRows: Int = DEFAULT_EMBED_ROW_LIMIT,
) {
    private val linkableIds = buildSet {
        searchEngine.graph.nodes.mapTo(this) { it.id.value }
        searchEngine.graph.nodeTypeIds.mapTo(this) { it.value }
        searchEngine.graph.relationTypeIds.mapTo(this) { it.value }
        searchEngine.graph.timelines.mapTo(this) { it.id.value }
    }

    init {
        require(maxRows > 0)
    }

    suspend fun render(directive: EmbedDirective, currentNodeId: String): EmbedRenderResult = when (directive) {
        is EmbedDirective.Query -> renderQuery(directive.query)
        is EmbedDirective.BackLink -> renderBackLinks(directive.relType, currentNodeId)
    }

    private suspend fun renderQuery(query: String): EmbedRenderResult {
        val result = searchEngine.queryGmql(
            query,
            options = GmqlExecutionOptions(
                profile = GmqlExecutionProfile.STATIC_WEB,
                maxResults = maxRows,
            ),
        )
        if (result.diagnostics.isNotEmpty()) {
            return EmbedRenderResult(
                diagnostics = result.diagnostics.map { EmbedDiagnostic(it.code, it.message) },
            )
        }
        return EmbedRenderResult(
            table = EmbedTable(
                columns = result.columns.map { EmbedColumn(it.name, it.type.embedTypeName()) },
                rows = result.rows.map { row ->
                    EmbedRow(row.values.map { value ->
                        EmbedCell(formatEmbedValue(value), embedTargetId(value))
                    })
                },
            ),
        )
    }

    private fun embedTargetId(value: GmqlValue): String? {
        val candidate = when (value) {
            is GmqlValue.NodeValue -> value.id.value
            is GmqlValue.StringValue -> value.value
            is GmqlValue.TypeRefValue -> value.name
            else -> null
        }
        return candidate.takeIf { it in linkableIds }
    }

    private fun renderBackLinks(relType: String, currentNodeId: String): EmbedRenderResult {
        if (searchEngine.graph.relationTypeIds.none { it.value == relType }) {
            return EmbedRenderResult(
                diagnostics = listOf(EmbedDiagnostic("GRAPHMD_EMBED_RELTYPE", "Unknown RelType '$relType'.")),
            )
        }
        val nodes = searchEngine.graph.nodes.associateBy { it.id }
        val relations = searchEngine.graph.relationAssertions
            .asSequence()
            .filter { it.relTypeId.value == relType && it.targetNodeId.value == currentNodeId }
            .sortedWith(compareBy({ it.sourceNodeId.value }, { it.stableKey.value }))
            .toList()
        if (relations.size > maxRows) {
            return EmbedRenderResult(
                diagnostics = listOf(
                    EmbedDiagnostic(
                        "GRAPHMD_EMBED_LIMIT",
                        "The backlink result exceeded the configured result limit of $maxRows rows.",
                    ),
                ),
            )
        }
        return EmbedRenderResult(
            table = EmbedTable(
                columns = listOf(
                    EmbedColumn("id", "string"),
                    EmbedColumn("type", "type-ref"),
                    EmbedColumn("validity", "temporal-extent"),
                ),
                rows = relations.map { relation ->
                    val source = nodes[relation.sourceNodeId]
                    EmbedRow(
                        listOf(
                            EmbedCell(relation.sourceNodeId.value, relation.sourceNodeId.value),
                            EmbedCell(source?.typeId?.value.orEmpty()),
                            EmbedCell(formatIntervalSet(relation.validTime)),
                        ),
                    )
                },
            ),
        )
    }
}

fun EmbedTable.toMarkdown(resolveTarget: (String) -> String? = { null }): String = buildString {
    append("| ").append(columns.joinToString(" | ") { escapeMarkdownCell(it.name) }).append(" |\n")
    append("| ").append(columns.joinToString(" | ") { "---" }).append(" |\n")
    rows.forEach { row ->
        append("| ")
        append(
            row.cells.joinToString(" | ") { cell ->
                val label = escapeMarkdownCell(cell.text)
                cell.targetId?.let(resolveTarget)?.let { href ->
                    "[$label](${escapeMarkdownHref(href)})"
                } ?: label
            },
        )
        append(" |\n")
    }
}

fun formatEmbedValue(value: GmqlValue): String = when (value) {
    is GmqlValue.StringValue -> value.value
    is GmqlValue.IntegerValue -> value.value.toString()
    is GmqlValue.DecimalValue -> value.value.toString()
    is GmqlValue.BooleanValue -> value.value.toString()
    GmqlValue.NullValue -> "null"
    is GmqlValue.NodeValue -> value.id.value
    is GmqlValue.RelationValue -> value.id.value.toString()
    is GmqlValue.TypeRefValue -> value.name
    is GmqlValue.CollectionValue -> value.values.joinToString(prefix = "[", postfix = "]", transform = ::formatEmbedValue)
    is GmqlValue.TemporalValue -> value.entries.joinToString(prefix = "[", postfix = "]") {
        "${formatEmbedValue(it.value)} @ ${formatIntervalSet(it.validTime)}"
    }
    is GmqlValue.TemporalExtentValue -> formatIntervalSet(value.value)
}

fun formatIntervalSet(value: IntervalSet): String {
    if (value.isUniversal) return "Anytime"
    if (value.isEmpty) return "Empty"
    return value.intervals.joinToString(", ") { interval ->
        val open = if (interval.start?.inclusive != false) "[" else "("
        val close = if (interval.end?.inclusive != false) "]" else ")"
        "${interval.timelineId.value}: $open${formatBoundary(interval.start, "-∞")}, " +
            "${formatBoundary(interval.end, "+∞")}$close"
    }
}

private fun formatBoundary(boundary: IntervalBoundary?, fallback: String): String =
    boundary?.value?.toString() ?: fallback

private fun GmqlType.embedTypeName(): String = when (this) {
    GmqlType.Any -> "any"
    GmqlType.String -> "string"
    GmqlType.Text -> "text"
    GmqlType.Integer -> "integer"
    GmqlType.Decimal -> "decimal"
    GmqlType.Boolean -> "boolean"
    GmqlType.Null -> "null"
    GmqlType.Node -> "node"
    GmqlType.Relation -> "relation"
    GmqlType.TypeRef -> "type-ref"
    GmqlType.TemporalExtent -> "temporal-extent"
    is GmqlType.Collection -> "collection<${elementType.embedTypeName()}>"
    is GmqlType.Temporal -> "temporal<${valueType.embedTypeName()}>"
}

private fun escapeMarkdownCell(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\\", "\\\\")
    .replace("|", "\\|")
    .replace("\r\n", "<br>")
    .replace("\r", "<br>")
    .replace("\n", "<br>")
    .replace("[", "\\[")
    .replace("]", "\\]")

private fun escapeMarkdownHref(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character in setOf('-', '_', '.', '~', '/')
        ) {
            append(character)
        } else {
            append('%')
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
