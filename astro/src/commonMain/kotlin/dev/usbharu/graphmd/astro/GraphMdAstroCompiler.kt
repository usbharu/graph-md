package dev.usbharu.graphmd.astro

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.Diagnostic
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
        val graph = GraphCompiler().compileSources(sources)
        val errors = graph.diagnostics.filter { it.severity == Severity.Error }
        val search = if (errors.isEmpty()) GraphSearchEngine.build(graph, sources).exportStatic() else null
        return AstroCompilation(graph, graph.diagnostics, search)
    }
}

data class AstroCompilation(
    val graph: GraphCompilationResult,
    val diagnostics: List<Diagnostic>,
    val search: StaticSearchBundle?,
) {
    val successful: Boolean
        get() = diagnostics.none { it.severity == Severity.Error }
}
