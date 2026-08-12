@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.usbharu.graphmd.astro

import dev.usbharu.graphmd.core.model.SourceDocument
import kotlin.js.JSON
import kotlin.js.JsExport

/** Narrow JSON API consumed by the Node process running Astro and Vite. */
@JsExport
object GraphMdAstro {
    fun compile(sourcesJson: String): GraphMdAstroCompilation {
        val encoded = JSON.parse<Array<dynamic>>(sourcesJson)
        val sources = encoded.map { source ->
            SourceDocument(
                text = source.text as String,
                sourcePath = source.path as String,
            )
        }
        return GraphMdAstroCompilation(GraphMdAstroCompiler().compile(sources))
    }
}

@JsExport
class GraphMdAstroCompilation internal constructor(
    private val result: AstroCompilation,
) {
    val successful: Boolean
        get() = result.successful

    fun diagnosticsJson(): String {
        val diagnostics = result.diagnostics.map { diagnostic ->
            val encoded = js("({})")
            encoded.severity = diagnostic.severity.name.lowercase()
            encoded.category = diagnostic.category.name
            encoded.message = diagnostic.message
            diagnostic.source?.let { source ->
                val encodedSource = js("({})")
                encodedSource.path = source.path
                encodedSource.documentId = source.documentId
                source.range?.let { range ->
                    val encodedRange = js("({})")
                    encodedRange.start = range.start
                    encodedRange.end = range.end
                    encodedSource.range = encodedRange
                }
                encoded.source = encodedSource
            }
            encoded
        }.toTypedArray()
        return JSON.stringify(diagnostics)
    }

    /** Static-search files keyed by their output filename. Empty on compilation errors. */
    fun searchFilesJson(): String {
        val encoded = js("({})")
        result.search?.files()?.forEach { (name, content) -> encoded[name] = content }
        return JSON.stringify(encoded)
    }

    /** Complete wiki view model consumed by Astro pages without an intermediate site.json file. */
    fun siteJson(base: String = "/"): String = WikiSiteEncoder(base, result.documents, result.graph).encode()

    /** Lightweight normalized graph for virtual modules and graph visualizations. */
    fun graphJson(): String {
        val encoded = js("({})")
        encoded.nodes = result.graph.nodes.map { node ->
            val item = js("({})")
            item.id = node.id
            item.type = node.type
            item.kind = node.kind.name
            item.url = node.url
            item
        }.toTypedArray()
        encoded.relations = result.graph.relations.map { relation ->
            val item = js("({})")
            item.from = relation.from
            item.to = relation.to
            item.type = relation.type
            item.label = relation.sourceLabel
            item
        }.toTypedArray()
        encoded.nodeTypes = result.graph.nodeTypes.map { type ->
            val item = js("({})")
            item.id = type.id
            item.ancestors = type.ancestorIds.toTypedArray()
            item
        }.toTypedArray()
        encoded.relTypes = result.graph.relTypes.map { type ->
            val item = js("({})")
            item.id = type.id
            item.ancestors = type.ancestorIds.toTypedArray()
            item
        }.toTypedArray()
        encoded.timelines = result.graph.timelines.map { timeline ->
            val item = js("({})")
            item.id = timeline.id
            item.axis = timeline.axisId
            item.domain = timeline.domainId
            item.parent = timeline.coordinateSystem.parentTimelineId
            item.lineageSource = timeline.lineage?.sourceTimelineId
            item.lineageKind = timeline.lineage?.kind?.name?.lowercase()
            item
        }.toTypedArray()
        return JSON.stringify(encoded)
    }
}
