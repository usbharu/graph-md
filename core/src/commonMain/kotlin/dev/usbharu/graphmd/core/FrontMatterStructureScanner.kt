package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.SourceRange

/**
 * A tolerant, position-preserving view of the block YAML subset accepted by
 * [GraphDocumentParser]. It is deliberately independent from the value parser:
 * analysis must continue to work while a document is being edited, but every
 * key is still associated with its structural path rather than matched by name
 * alone.
 */
internal data class FrontMatterStructure(
    val entries: List<FrontMatterEntry>,
    val scalars: List<FrontMatterScalar>,
    val rootIndent: Int?,
) {
    fun rootEntries(key: String): List<FrontMatterEntry> =
        entries.filter { it.path.size == 1 && it.key == key && it.indent == rootIndent }

    fun scalarsFor(entry: FrontMatterEntry): List<FrontMatterScalar> =
        scalars.filter { it.owner == entry.index }
}

internal data class FrontMatterEntry(
    val index: Int,
    val key: String,
    val path: List<String>,
    val indent: Int,
    val keyRange: SourceRange,
    val valueStart: Int,
)

internal data class FrontMatterScalar(
    val value: String,
    val raw: String,
    val path: List<String>,
    val range: SourceRange,
    val owner: Int,
)

internal class FrontMatterStructureScanner {
    fun scan(
        lines: List<String>,
        lineStarts: List<Int>,
        startLine: Int,
        endLine: Int,
    ): FrontMatterStructure {
        val entries = mutableListOf<FrontMatterEntry>()
        val scalars = mutableListOf<FrontMatterScalar>()
        val frames = mutableListOf<PathFrame>()
        var rootIndent: Int? = null

        for (lineIndex in startLine until endLine) {
            val original = lines.getOrNull(lineIndex) ?: continue
            if (original.isBlank()) continue
            val physicalIndent = indentOf(original)
            val withoutIndent = original.drop(physicalIndent)
            if (withoutIndent.startsWith("#")) continue

            val dashLength = sequencePrefixLength(withoutIndent)
            val contentStart = physicalIndent + dashLength
            val content = stripYamlComment(original.substring(contentStart)).trimEnd()
            if (content.isBlank()) continue

            if (dashLength > 0) {
                while (frames.lastOrNull()?.indent?.let { it >= physicalIndent } == true) frames.removeLast()
            } else {
                while (frames.lastOrNull()?.indent?.let { it >= physicalIndent } == true) frames.removeLast()
            }
            while (
                frames.lastOrNull()?.let {
                    it.childKind == BlockKind.LIST &&
                        it.childIndent == physicalIndent &&
                        dashLength == 0
                } == true
            ) {
                // A block sequence only continues with another dash at its
                // own indentation. A mapping line at that indentation belongs
                // to the surrounding sequence-item mapping.
                frames.removeLast()
            }
            frames.lastOrNull()
                ?.takeIf { it.pendingChildIndent && physicalIndent > it.indent }
                ?.let { pending ->
                    // MiniYamlParser accepts the first nested block of a
                    // sequence item's initial empty key at any indentation
                    // deeper than the dash. Once observed, that indentation
                    // is the boundary which distinguishes nested content from
                    // later direct siblings of the sequence item.
                    pending.indent = physicalIndent - 1
                    pending.pendingChildIndent = false
                    pending.childIndent = physicalIndent
                    pending.childKind = if (dashLength > 0) BlockKind.LIST else BlockKind.MAP
                }
            val parentPath = frames.lastOrNull()?.path.orEmpty()

            val split = splitKeyValue(content)
            if (split == null) {
                if (dashLength > 0) {
                    if (content.trimStart().startsWith("[")) continue
                    addScalarTokens(
                        rawValue = content,
                        absoluteStart = lineStarts[lineIndex] + contentStart,
                        path = parentPath,
                        owner = frames.lastOrNull()?.owner ?: -1,
                        destination = scalars,
                    )
                }
                continue
            }

            if (dashLength > 0) {
                // Continuation mapping entries belonging to the same sequence item
                // have a greater physical indentation than this synthetic frame.
                frames += PathFrame(physicalIndent, parentPath, frames.lastOrNull()?.owner ?: -1)
            }
            val keyIndent = contentStart
            rootIndent = rootIndent ?: keyIndent
            val path = parentPath + split.key
            val entryIndex = entries.size
            val absoluteContentStart = lineStarts[lineIndex] + contentStart
            val entry = FrontMatterEntry(
                index = entryIndex,
                key = split.key,
                path = path,
                indent = keyIndent,
                keyRange = SourceRange(
                    absoluteContentStart + split.keyStart,
                    absoluteContentStart + split.keyEnd,
                ),
                valueStart = absoluteContentStart + split.valueStart,
            )
            entries += entry

            if (split.value.isBlank()) {
                frames += PathFrame(
                    indent = if (dashLength > 0) physicalIndent else keyIndent,
                    path = path,
                    owner = entryIndex,
                    pendingChildIndent = dashLength > 0,
                )
            } else {
                addScalarTokens(
                    rawValue = split.value,
                    absoluteStart = absoluteContentStart + split.valueStart,
                    path = path,
                    owner = entryIndex,
                    destination = scalars,
                )
            }
        }
        return FrontMatterStructure(entries, scalars, rootIndent)
    }

    private fun addScalarTokens(
        rawValue: String,
        absoluteStart: Int,
        path: List<String>,
        owner: Int,
        destination: MutableList<FrontMatterScalar>,
    ) {
        val trimmedStart = rawValue.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val trimmed = rawValue.drop(trimmedStart).trimEnd()
        if (trimmed.startsWith("[") && !trimmed.endsWith("]")) return
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            splitYamlFlowItems(trimmed.substring(1, trimmed.length - 1)).forEach { item ->
                val raw = item.raw.trim()
                if (raw.isEmpty()) return@forEach
                if (raw.startsWith("[") && raw.endsWith("]")) return@forEach
                val leading = item.raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val start = absoluteStart + trimmedStart + 1 + item.start + leading
                val decoded = decodeYamlScalar(raw)
                destination += FrontMatterScalar(
                    decoded,
                    raw,
                    path,
                    scalarRange(raw, start, decoded),
                    owner,
                )
            }
            return
        }
        val decoded = decodeYamlScalar(trimmed)
        destination += FrontMatterScalar(
            decoded,
            trimmed,
            path,
            scalarRange(trimmed, absoluteStart + trimmedStart, decoded),
            owner,
        )
    }

    private fun scalarRange(raw: String, start: Int, decoded: String): SourceRange {
        val quoted = raw.length >= 2 &&
            ((raw.first() == '"' && raw.last() == '"') || (raw.first() == '\'' && raw.last() == '\''))
        val canonical = decoded.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]*"))
        return if (quoted && !canonical) {
            SourceRange(start + 1, start + raw.length - 1)
        } else {
            SourceRange(start, start + raw.length)
        }
    }

    private fun splitKeyValue(content: String): KeyValueSplit? {
        val colon = findYamlMappingColon(content)
        if (colon <= 0) return null
        val rawKey = content.substring(0, colon)
        val keyStart = rawKey.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val keyEnd = rawKey.indexOfLast { !it.isWhitespace() } + 1
        val keyToken = rawKey.substring(keyStart, keyEnd)
        val key = decodeYamlScalar(keyToken)
        if (key.isEmpty()) return null
        val quotedKey = keyToken.length >= 2 &&
            ((keyToken.first() == '"' && keyToken.last() == '"') ||
                (keyToken.first() == '\'' && keyToken.last() == '\''))
        val rangeStart = if (quotedKey) keyStart + 1 else keyStart
        val rangeEnd = if (quotedKey) keyEnd - 1 else keyEnd
        val rawRest = content.substring(colon + 1)
        val restLeading = rawRest.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawRest.length else it }
        val valueStart = colon + 1 + restLeading
        return KeyValueSplit(key, rangeStart, rangeEnd, valueStart, content.substring(valueStart))
    }

    private fun sequencePrefixLength(content: String): Int {
        if (!content.startsWith("-")) return 0
        if (content.length == 1) return 1
        if (!content[1].isWhitespace()) return 0
        var index = 1
        while (content.getOrNull(index)?.isWhitespace() == true) index++
        return index
    }

    private fun indentOf(line: String): Int =
        line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }

    private data class PathFrame(
        var indent: Int,
        val path: List<String>,
        val owner: Int,
        var pendingChildIndent: Boolean = false,
        var childIndent: Int? = null,
        var childKind: BlockKind? = null,
    )
    private enum class BlockKind { MAP, LIST }
    private data class KeyValueSplit(
        val key: String,
        val keyStart: Int,
        val keyEnd: Int,
        val valueStart: Int,
        val value: String,
    )
}
