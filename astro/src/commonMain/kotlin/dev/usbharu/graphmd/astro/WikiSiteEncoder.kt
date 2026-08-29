package dev.usbharu.graphmd.astro

import dev.usbharu.graphmd.core.BodySyntaxExtractor
import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.GraphSearchEngine
import dev.usbharu.graphmd.query.embed.EmbedEngine
import dev.usbharu.graphmd.query.embed.EmbedTable
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal class WikiSiteEncoder(
    base: String,
    private val documents: List<GraphDocument>,
    private val graph: GraphCompilationResult,
    sources: List<SourceDocument> = emptyList(),
) {
    private val base = normalizeBase(base)
    private val routes = documents.associate { it.id to "${this.base}documents/${safeSlug(it.id)}/" }
    private val embedEngine = EmbedEngine(GraphSearchEngine.build(graph, sources))

    fun encode(): String {
        val incoming = graph.relations.groupBy { it.to }
        val docs = documents.map { document -> encodeDocument(document, incoming[document.id].orEmpty()) }
        val nodes = graph.nodes.map { node ->
            jsonObject(
                "data" to jsonObject(
                    "id" to jsonString(node.id),
                    "label" to jsonString(titleOf(node.id)),
                    "route" to jsonNullableString(routes[node.id]),
                    "kind" to jsonString(node.kind.name),
                    "type" to jsonString(node.type),
                ),
            )
        }
        val edges = graph.relations.mapIndexed { index, relation ->
            jsonObject(
                "data" to jsonObject(
                    "id" to jsonString("e$index"),
                    "source" to jsonString(relation.from),
                    "target" to jsonString(relation.to),
                    "label" to jsonString(relation.type),
                ),
            )
        }
        val timelineNodes = graph.timelines.map { timeline ->
            jsonObject(
                "data" to jsonObject(
                    "id" to jsonString(timeline.id),
                    "label" to jsonString(titleOf(timeline.id)),
                    "route" to jsonNullableString(routes[timeline.id]),
                    "domain" to jsonString(timeline.domainId),
                ),
            )
        }
        val timelineEdges = buildList {
            graph.timelines.forEach { timeline ->
                timeline.coordinateSystem.parentTimelineId?.let { parent ->
                    add(timelineEdge("same-axis:$parent:${timeline.id}", parent, timeline.id, "same axis", "sameAxis"))
                }
                timeline.lineage?.let { lineage ->
                    add(
                        timelineEdge(
                            "lineage:${lineage.sourceTimelineId}:${timeline.id}",
                            lineage.sourceTimelineId,
                            timeline.id,
                            lineage.kind.name.lowercase(),
                            "lineage",
                        ),
                    )
                }
            }
            graph.temporalModel.mappings.forEach { mapping ->
                add(
                    timelineEdge(
                        "mapping:${mapping.id}",
                        mapping.sourceTimelineId,
                        mapping.targetTimelineId,
                        mapping.kind.name.lowercase(),
                        "mapping",
                    ),
                )
            }
        }
        return jsonObject(
            "base" to jsonString(base),
            "documents" to jsonArray(docs),
            "routes" to JsonValue.Object(routes.mapValues { jsonString(it.value) }),
            "graph" to jsonObject("nodes" to jsonArray(nodes), "edges" to jsonArray(edges)),
            "timelineGraph" to jsonObject("nodes" to jsonArray(timelineNodes), "edges" to jsonArray(timelineEdges)),
        ).encode()
    }

    private fun encodeDocument(document: GraphDocument, backlinks: List<NormalizedRelation>): JsonValue {
        val node = graph.nodes.firstOrNull { it.id == document.id }
        val nodeType = graph.nodeTypes.firstOrNull { it.id == document.id }
        val relType = graph.relTypes.firstOrNull { it.id == document.id }
        val timeline = graph.timelines.firstOrNull { it.id == document.id }
        val properties = node?.propEntries?.let(::propertyEntriesToJson)
            ?: timeline?.props?.entries?.map { (name, value) ->
                jsonObject(
                    "name" to jsonString(name),
                    "value" to value.toJson(),
                    "validTime" to jsonArray(emptyList()),
                    "fallback" to jsonBoolean(false),
                )
            }?.let(::jsonArray)
            ?: jsonArray(emptyList())
        val schema = (nodeType?.props ?: relType?.props).orEmpty().entries.map { (name, prop) ->
            jsonObject("name" to jsonString(name), "schema" to prop.toJson())
        }
        return jsonObject(
            "id" to jsonString(document.id),
            "slug" to jsonString(safeSlug(document.id)),
            "route" to jsonString(routes.getValue(document.id)),
            "title" to jsonString(firstHeading(document.body) ?: document.id),
            "kind" to jsonString(document.kind.name),
            "type" to jsonNullableString(node?.type),
            "url" to jsonNullableString(node?.url?.let(::safeDocumentUrl)),
            "body" to jsonString(document.body),
            "embeds" to encodeEmbeds(document),
            "properties" to properties,
            "schema" to jsonArray(schema),
            "nodeType" to (encodeNodeType(document as? NodeTypeDocument) ?: JsonValue.Null),
            "relationUsage" to jsonArray(encodeRelationUsage(document)),
            "timeline" to (timeline?.let(::encodeTimeline) ?: JsonValue.Null),
            "backlinks" to jsonArray(backlinks.map { relation ->
                jsonObject(
                    "id" to jsonString(relation.from),
                    "type" to jsonString(relation.type),
                    "route" to jsonNullableString(routes[relation.from]),
                )
            }),
        )
    }

    private fun encodeEmbeds(document: GraphDocument): JsonValue {
        if (document.kind != DocumentKind.Node && document.kind != DocumentKind.Media) {
            return jsonArray(emptyList())
        }
        val blocks = BodySyntaxExtractor().extract(document.body, document.sourcePath, document.id).blocks
        return jsonArray(blocks.mapNotNull { block ->
            val directive = block.embed ?: return@mapNotNull null
            val result = runAstroSuspend { embedEngine.render(directive, document.id) }
            val (kind, value) = when (directive) {
                is EmbedDirective.Query -> "query" to directive.query
                is EmbedDirective.BackLink -> "back-link" to directive.relType
            }
            if (result.isSuccess) {
                jsonObject(
                    "kind" to jsonString(kind),
                    "value" to jsonString(value),
                    "status" to jsonString("ready"),
                    "table" to encodeEmbedTable(checkNotNull(result.table)),
                )
            } else {
                jsonObject(
                    "kind" to jsonString(kind),
                    "value" to jsonString(value),
                    "status" to jsonString("error"),
                    "message" to jsonString(result.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }),
                )
            }
        })
    }

    private fun encodeEmbedTable(table: EmbedTable): JsonValue = jsonObject(
        "columns" to jsonArray(table.columns.map { column ->
            jsonObject("name" to jsonString(column.name), "type" to jsonString(column.type))
        }),
        "rows" to jsonArray(table.rows.map { row ->
            jsonObject("cells" to jsonArray(row.cells.map { cell ->
                jsonObject(
                    "text" to jsonString(cell.text),
                    "targetId" to jsonNullableString(cell.targetId),
                )
            }))
        }),
    )

    private fun encodeNodeType(current: NodeTypeDocument?): JsonValue? = current?.let {
        fun typeLink(id: String): JsonValue = jsonObject(
            "id" to jsonString(id),
            "title" to jsonString(titleOf(id)),
            "route" to jsonNullableString(routes[id]),
        )
        val children = documents.filterIsInstance<NodeTypeDocument>()
            .filter { current.id in it.extends }
            .sortedBy { it.id }
        val usage = graph.nodes.filter { it.type == current.id }.sortedBy { it.id }.map { usedBy ->
            jsonObject(
                "id" to jsonString(usedBy.id),
                "title" to jsonString(titleOf(usedBy.id)),
                "kind" to jsonString(usedBy.kind.name),
                "route" to jsonNullableString(routes[usedBy.id]),
            )
        }
        jsonObject(
            "parents" to jsonArray(current.extends.map(::typeLink)),
            "children" to jsonArray(children.map { typeLink(it.id) }),
            "usage" to jsonArray(usage),
        )
    }

    private fun encodeRelationUsage(document: GraphDocument): List<JsonValue> =
        if (document.kind != DocumentKind.RelType) emptyList() else graph.relations
            .filter { it.type == document.id }
            .map { relation ->
                jsonObject(
                    "from" to jsonString(relation.from),
                    "fromRoute" to jsonNullableString(routes[relation.from]),
                    "to" to jsonString(relation.to),
                    "toRoute" to jsonNullableString(routes[relation.to]),
                    "label" to jsonString(relation.sourceLabel),
                    "properties" to propertyEntriesToJson(relation.propEntries),
                )
            }

    private fun encodeTimeline(current: NormalizedTimeline): JsonValue {
        val mappings = graph.temporalModel.mappings.filter {
            it.sourceTimelineId == current.id || it.targetTimelineId == current.id
        }.map { mapping ->
            jsonObject(
                "direction" to jsonString(if (mapping.sourceTimelineId == current.id) "outgoing" else "incoming"),
                "source" to jsonString(mapping.sourceTimelineId),
                "sourceRoute" to jsonNullableString(routes[mapping.sourceTimelineId]),
                "target" to jsonString(mapping.targetTimelineId),
                "targetRoute" to jsonNullableString(routes[mapping.targetTimelineId]),
                "definition" to mapping.toJson(),
            )
        }
        return jsonObject(
            "id" to jsonString(current.id),
            "axis" to jsonString(current.axisId),
            "domain" to jsonString(current.domainId),
            "coordinate" to current.coordinate.toJson(),
            "parent" to jsonNullableString(current.coordinateSystem.parentTimelineId),
            "parentRoute" to jsonNullableString(current.coordinateSystem.parentTimelineId?.let(routes::get)),
            "lineage" to (current.lineage?.toJson() ?: JsonValue.Null),
            "lineageRoute" to jsonNullableString(current.lineage?.sourceTimelineId?.let(routes::get)),
            "mappings" to jsonArray(mappings),
        )
    }

    private fun timelineEdge(id: String, source: String, target: String, label: String, kind: String): JsonValue =
        jsonObject(
            "data" to jsonObject(
                "id" to jsonString(id),
                "source" to jsonString(source),
                "target" to jsonString(target),
                "label" to jsonString(label),
                "kind" to jsonString(kind),
            ),
        )

    private fun titleOf(id: String): String =
        firstHeading(documents.firstOrNull { it.id == id }?.body.orEmpty()) ?: id
}

internal fun safeSlug(id: String): String = buildString {
    id.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character in 'a'..'z' || character in '0'..'9' || character in setOf('_', '-')) append(character)
        else append('~').append(value.toString(16).uppercase().padStart(2, '0'))
    }
}

private fun safeDocumentUrl(url: String): String? {
    val value = url.trim()
    if (value.isEmpty() || value.startsWith("//") || '\\' in value || value.any { it.code < 0x20 }) return null
    val scheme = URL_SCHEME.find(value)?.groupValues?.get(1)?.lowercase()
    return if (scheme == null || scheme == "http" || scheme == "https") value else null
}

private val URL_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

private fun firstHeading(body: String): String? = body.lineSequence().map(String::trim)
    .firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.trimEnd('#')?.trim()
    ?.takeIf(String::isNotEmpty)

private fun normalizeBase(base: String): String = "/" + base.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

private fun <T> runAstroSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "Astro embed execution suspended unexpectedly" }.getOrThrow()
}
