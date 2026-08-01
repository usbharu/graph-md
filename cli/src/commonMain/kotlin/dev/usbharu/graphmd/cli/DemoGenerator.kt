package dev.usbharu.graphmd.cli

import kotlin.math.max
import kotlin.random.Random

internal data class DemoDocument(
    val fileName: String,
    val text: String,
    val kind: CliKind,
)

internal data class DemoPlan(
    val requestedCount: Int,
    val seed: Int,
    val timelineCount: Int,
    val nodeTypeCount: Int,
    val relTypeCount: Int,
    val nodeCount: Int,
    val mediaCount: Int,
) {
    val generatedCount: Int =
        timelineCount + nodeTypeCount + relTypeCount + nodeCount + mediaCount

    val counts: Map<CliKind, Int> = mapOf(
        CliKind.Node to nodeCount,
        CliKind.Media to mediaCount,
        CliKind.Link to 0,
        CliKind.NodeType to nodeTypeCount,
        CliKind.RelType to relTypeCount,
        CliKind.Timeline to timelineCount,
    )

    fun documents(): Sequence<DemoDocument> = sequence {
        val random = Random(seed)
        repeat(timelineCount) { index ->
            yield(timelineDocument(index, random))
        }
        repeat(nodeTypeCount) { index ->
            yield(nodeTypeDocument(index, random))
        }
        repeat(relTypeCount) { index ->
            yield(relTypeDocument(index, random))
        }
        repeat(nodeCount + mediaCount) { index ->
            yield(entityDocument(index, index >= nodeCount, random))
        }
    }

    fun fileNameAt(index: Int): String {
        require(index in 0 until generatedCount)
        return when {
            index < timelineCount -> timelineFileName(index)
            index < timelineCount + nodeTypeCount ->
                nodeTypeFileName(index - timelineCount)
            index < timelineCount + nodeTypeCount + relTypeCount ->
                relTypeFileName(index - timelineCount - nodeTypeCount)
            else -> {
                val entityIndex = index - timelineCount - nodeTypeCount - relTypeCount
                entityFileName(entityIndex, entityIndex >= nodeCount)
            }
        }
    }

    private fun timelineDocument(index: Int, random: Random): DemoDocument {
        val id = timelineId(index)
        val parent = randomParentIndices(index, random).firstOrNull()?.let(::timelineId)
        val language = if (index % 2 == 0) {
            "この時系列は出来事を整理するための基準です。"
        } else {
            "This timeline provides a consistent frame for recorded events."
        }
        return DemoDocument(
            fileName = timelineFileName(index),
            kind = CliKind.Timeline,
            text = buildString {
                appendLine("---")
                appendLine("id: $id")
                appendLine("kind: Timeline")
                parent?.let { appendLine("sameAxisAs: $it") }
                appendLine("---")
                appendLine()
                appendLine("# $id")
                appendLine()
                appendLine(language)
            },
        )
    }

    private fun nodeTypeDocument(index: Int, random: Random): DemoDocument {
        val id = nodeTypeId(index)
        val parents = randomParentIndices(index, random).map(::nodeTypeId)
        return DemoDocument(
            fileName = nodeTypeFileName(index),
            kind = CliKind.NodeType,
            text = buildString {
                appendLine("---")
                appendLine("id: $id")
                appendLine("kind: NodeType")
                appendYamlList("extends", parents)
                appendLine("props:")
                if (index == 0) {
                    appendLine("  title:")
                    appendLine("    type: string")
                    appendLine("    required: true")
                    appendLine("  score:")
                    appendLine("    type: number")
                    appendLine("    required: true")
                    appendLine("  summary:")
                    appendLine("    type: string")
                    appendLine("  tags:")
                    appendLine("    type: array")
                    appendLine("    required: true")
                    appendLine("    items:")
                    appendLine("      type: string")
                    appendLine("  observedAt:")
                    appendLine("    type: instant")
                    appendLine("    required: true")
                    appendLine("    timeline: ${timelineId(0)}")
                } else {
                    appendLine("  detail_$index:")
                    appendLine("    type: string")
                }
                appendLine("---")
                appendLine()
                appendLine("# $id")
                appendLine()
                appendLine(
                    if (index % 2 == 0) {
                        "この型は関連する記録を分類します。"
                    } else {
                        "This type classifies related records."
                    },
                )
            },
        )
    }

    private fun relTypeDocument(index: Int, random: Random): DemoDocument {
        val id = relTypeId(index)
        val parents = randomParentIndices(index, random).map(::relTypeId)
        return DemoDocument(
            fileName = relTypeFileName(index),
            kind = CliKind.RelType,
            text = buildString {
                appendLine("---")
                appendLine("id: $id")
                appendLine("kind: RelType")
                appendYamlList("extends", parents)
                if (index == 0) {
                    appendLine("from:")
                    appendLine("  - ${nodeTypeId(0)}")
                    appendLine("to:")
                    appendLine("  - ${nodeTypeId(0)}")
                    appendLine("props:")
                    appendLine("  weight:")
                    appendLine("    type: number")
                    appendLine("    required: true")
                    appendLine("  recordedAt:")
                    appendLine("    type: instant")
                    appendLine("    required: true")
                    appendLine("    timeline: ${timelineId(0)}")
                }
                appendLine("---")
                appendLine()
                appendLine("# $id")
                appendLine()
                appendLine(
                    if (index % 2 == 0) {
                        "この関係は二つの記録のつながりを表します。"
                    } else {
                        "This relation connects two compatible records."
                    },
                )
            },
        )
    }

    private fun entityDocument(index: Int, media: Boolean, random: Random): DemoDocument {
        val id = entityId(index)
        val typeIndex = random.nextInt(nodeTypeCount)
        val type = nodeTypeId(typeIndex)
        val title = if (index % 2 == 0) {
            "${japaneseAdjectives.random(random)}${japaneseNouns.random(random)} $index"
        } else {
            "${englishAdjectives.random(random)} ${englishNouns.random(random)} $index"
        }
        val score = random.nextInt(10, 100)
        val observedAt = random.nextInt(1, 10_000)
        val documentTimeline = timelineId(random.nextInt(timelineCount))
        val documentFrom = random.nextInt(0, 9_000)
        val documentTo = documentFrom + random.nextInt(10, 1_000)
        val entityCount = nodeCount + mediaCount
        val linkCount = minOf(3, entityCount - 1)
        val targetIndices = randomTargetIndices(index, entityCount, random)
            .take(random.nextInt(1, linkCount + 1))
        val links = targetIndices.joinToString("\n") { targetIndex ->
            val relType = relTypeId(random.nextInt(relTypeCount))
            val timeline = timelineId(random.nextInt(timelineCount))
            val from = random.nextInt(0, 9_000)
            val to = from + random.nextInt(10, 1_000)
            val weight = random.nextInt(10, 100).toDouble() / 100
            val recordedAt = random.nextInt(1, 10_000)
            val target = entityId(targetIndex)
            "@link(validTime=$timeline(from=$from,to=$to)){weight=$weight,recordedAt=$recordedAt}" +
                "[${displayName(target)}]($target $relType)"
        }
        val prose = if (index % 2 == 0) {
            """
                $title は${japanesePlaces.random(random)}で記録されました。複数の資料を比較し、背景と変化を短くまとめています。
                概要は @props{summary(validTime=$documentTimeline(from=$documentFrom,to=$documentTo)) = "継続的に更新される記録"} として整理されています。
            """.trimIndent()
        } else {
            """
                $title was recorded near ${englishPlaces.random(random)}. It summarizes the context and recent changes in a few concise observations.
                Its summary is @props{summary(validTime=$documentTimeline(from=$documentFrom,to=$documentTo)) = "A record updated over time"}.
            """.trimIndent()
        }
        return DemoDocument(
            fileName = entityFileName(index, media),
            kind = if (media) CliKind.Media else CliKind.Node,
            text = buildString {
                appendLine("---")
                appendLine("id: $id")
                appendLine("kind: ${if (media) "Media" else "Node"}")
                appendLine("type: $type")
                if (media) appendLine("url: https://example.invalid/media/$id.jpg")
                appendLine("props:")
                appendLine("  title: \"${yamlEscape(title)}\"")
                appendLine("  score: $score")
                appendLine("  tags:")
                appendLine("    - ${if (index % 2 == 0) "記録" else "record"}")
                appendLine("    - benchmark")
                appendLine("  observedAt:")
                appendLine("    timeline: ${timelineId(0)}")
                appendLine("    value: $observedAt")
                if (typeIndex > 0) {
                    appendLine(
                        "  detail_$typeIndex: \"" +
                            yamlEscape(if (index % 2 == 0) "追加の観測情報" else "Additional observation") +
                            "\"",
                    )
                }
                appendLine("validTime:")
                appendLine("  - timeline: $documentTimeline")
                appendLine("    from: $documentFrom")
                appendLine("    to: $documentTo")
                appendLine("---")
                appendLine()
                appendLine("# $title")
                appendLine()
                appendLine(prose)
                appendLine()
                appendLine(links)
            },
        )
    }

    private fun randomParentIndices(index: Int, random: Random): List<Int> {
        if (index == 0) return emptyList()
        if (index == 1 || random.nextBoolean()) return listOf(random.nextInt(index))
        val first = random.nextInt(index)
        var second = random.nextInt(index - 1)
        if (second >= first) second++
        return if (first < second) listOf(first, second) else listOf(second, first)
    }

    private fun randomTargetIndices(self: Int, entityCount: Int, random: Random): List<Int> {
        val count = minOf(3, entityCount - 1)
        val targets = LinkedHashSet<Int>(count)
        while (targets.size < count) {
            var candidate = random.nextInt(entityCount - 1)
            if (candidate >= self) candidate++
            targets += candidate
        }
        return targets.toList()
    }

    private fun StringBuilder.appendYamlList(name: String, values: List<String>) {
        if (values.isEmpty()) return
        appendLine("$name:")
        values.forEach { appendLine("  - $it") }
    }
}

internal object DemoGenerator {
    fun plan(requestedCount: Int, requestedSeed: Int?): DemoPlan {
        val total = max(requestedCount, 8)
        val timelineCount = max(2, total / 50)
        val nodeTypeCount = max(2, total / 40)
        val relTypeCount = max(2, total / 50)
        val entityCount = total - timelineCount - nodeTypeCount - relTypeCount
        val mediaCount = max(1, entityCount / 20)
        return DemoPlan(
            requestedCount = requestedCount,
            seed = requestedSeed ?: Random.Default.nextInt(),
            timelineCount = timelineCount,
            nodeTypeCount = nodeTypeCount,
            relTypeCount = relTypeCount,
            nodeCount = entityCount - mediaCount,
            mediaCount = mediaCount,
        )
    }
}

private fun timelineId(index: Int): String = "Timeline_${index.padded()}"
private fun nodeTypeId(index: Int): String = "NodeType_${index.padded()}"
private fun relTypeId(index: Int): String = "rel_${index.padded()}"
private fun entityId(index: Int): String = "entity_${index.padded(9)}"
private fun timelineFileName(index: Int): String = "timeline-${index.padded()}.md"
private fun nodeTypeFileName(index: Int): String = "node-type-${index.padded()}.md"
private fun relTypeFileName(index: Int): String = "rel-type-${index.padded()}.md"
private fun entityFileName(index: Int, media: Boolean): String =
    "${if (media) "media" else "node"}-${index.padded(9)}.md"

private fun Int.padded(width: Int = 6): String = toString().padStart(width, '0')
private fun displayName(id: String): String = id.replace('_', ' ')
private fun yamlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

private val japaneseAdjectives = listOf("静かな", "新しい", "歴史的な", "小さな", "重要な")
private val japaneseNouns = listOf("研究計画", "地域資料", "観測記録", "文化活動", "共同調査")
private val japanesePlaces = listOf("東京", "札幌", "京都", "福岡", "瀬戸内")
private val englishAdjectives = listOf("Quiet", "Emerging", "Historic", "Regional", "Collaborative")
private val englishNouns = listOf("Research Project", "Field Record", "Archive", "Survey", "Study")
private val englishPlaces = listOf("Tokyo", "Sapporo", "Kyoto", "Fukuoka", "the Seto Inland Sea")
