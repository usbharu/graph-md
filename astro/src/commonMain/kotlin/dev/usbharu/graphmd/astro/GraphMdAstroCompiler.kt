package dev.usbharu.graphmd.astro

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.Diagnostic
import dev.usbharu.graphmd.core.model.GraphDocument
import dev.usbharu.graphmd.core.model.GraphCompilationResult
import dev.usbharu.graphmd.core.model.Severity
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.GraphSearchEngine
import dev.usbharu.graphmd.query.persistence.StaticSearchBundle

/**
 * Build-time GraphMD compiler used by the Astro integration.
 *
 * This module deliberately owns the web build boundary. Core remains unaware
 * of Astro, file watching, virtual modules, and static search artifacts.
 */
class GraphMdAstroCompiler {
    fun compile(sources: List<SourceDocument>): AstroCompilation {
        val compiler = GraphCompiler()
        val parsed = sources.map { compiler.parseDocument(it.text, it.sourcePath) }
        val graph = compiler.compileParsed(parsed)
        val errors = graph.diagnostics.filter { it.severity == Severity.Error }
        val search = if (errors.isEmpty()) GraphSearchEngine.build(graph, sources).exportStatic() else null
        return AstroCompilation(
            graph = graph,
            documents = parsed.mapNotNull { it.document }.sortedBy { it.id },
            diagnostics = graph.diagnostics,
            search = search,
        )
    }
}

data class AstroCompilation(
    val graph: GraphCompilationResult,
    val documents: List<GraphDocument>,
    val diagnostics: List<Diagnostic>,
    val search: StaticSearchBundle?,
) {
    val successful: Boolean
        get() = diagnostics.none { it.severity == Severity.Error }
}
