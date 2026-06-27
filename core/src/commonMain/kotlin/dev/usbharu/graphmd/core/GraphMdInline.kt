@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.rawObjectToJsonString
import kotlin.js.JsExport

@JsExport
data class RelationParts(val target: String, val relType: String)

@JsExport
object GraphMdInline {
    public fun parseRelationTargetAndType(inside: String): RelationParts? =
        RelationTargetParser.parse(inside)?.let { RelationParts(it.first, it.second) }

    public fun parseInlineObjectJson(content: String): String =
        rawObjectToJsonString(InlinePropsParser(content).parseObject())
}
