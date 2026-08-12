@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.rawObjectToJsonString
import kotlin.js.JsExport

@JsExport
data class RelationParts(val target: String, val relType: String)

@JsExport
data class EmbedParts(val kind: String, val value: String)

@JsExport
object GraphMdInline {
    public fun parseRelationTargetAndType(inside: String): RelationParts? =
        RelationTargetParser.parse(inside)?.let { RelationParts(it.first, it.second) }

    public fun parseInlineObjectJson(content: String): String =
        rawObjectToJsonString(InlinePropsParser(content).parseObject())

    public fun parsePropsDirectiveJson(content: String): String {
        val extracted = BodySyntaxExtractor().extract(content, "<inline>", "<inline>")
        if (extracted.diagnostics.isNotEmpty() || extracted.propsBlocks.size != 1) {
            throw InlinePropsParseException(extracted.diagnostics.firstOrNull()?.message ?: "Invalid @props directive")
        }
        return rawObjectToJsonString(dev.usbharu.graphmd.core.model.RawObject(extracted.propsBlocks.single().props))
    }

    public fun isValidBlockHeader(content: String): Boolean =
        try {
            BodyBlockHeaderParser.parse(content)
            true
        } catch (_: InlinePropsParseException) {
            false
        }

    public fun parseEmbedHeader(content: String): EmbedParts? =
        when (val embed = BodyBlockHeaderParser.parse(content).embed) {
            is dev.usbharu.graphmd.core.model.EmbedDirective.Query -> EmbedParts("query", embed.query)
            is dev.usbharu.graphmd.core.model.EmbedDirective.BackLink -> EmbedParts("back-link", embed.relType)
            null -> null
        }
}
