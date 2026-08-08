package dev.usbharu.graphmd.cli

import kotlin.math.max
import kotlin.random.Random

internal data class DemoDocument(
    val fileName: String,
    val text: String,
    val kind: CliKind,
)

private enum class DemoTimelineFeature {
    BaseNumber,
    SameAxisAlias,
    NumberAffine,
    Gregorian,
    Julian,
    OffsetCalendar,
    AstronomicalCalendar,
    Era,
    Frame,
    NonDropTimecode,
    DropTimecode,
    Fork,
    Simulation,
    RecordingWithMappings,
    Edit,
    Resample,
    Copy,
    Derived,
}

private data class DemoTemporalRange(
    val from: String,
    val to: String,
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
    private val coreTimelineFeatures: List<DemoTimelineFeature> =
        listOf(DemoTimelineFeature.BaseNumber) +
            DemoTimelineFeature.entries
                .filterNot { it == DemoTimelineFeature.BaseNumber }
                .shuffled(Random(seed xor 0x51ED270B))

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
        val feature = timelineFeature(index)
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
                when (feature) {
                    DemoTimelineFeature.BaseNumber -> Unit
                    DemoTimelineFeature.SameAxisAlias -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.BaseNumber)}")
                        appendLine("aliases:")
                        appendLine("  - DemoClock_${random.nextInt(100, 1_000)}")
                        appendLine("props:")
                        appendLine("  label:")
                        appendLine("    default: Demo timeline $index")
                        appendLine("    ja: デモ時系列 $index")
                        appendLine("  note: Generated alias coordinate system")
                    }
                    DemoTimelineFeature.NumberAffine -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.BaseNumber)}")
                        appendLine("scale: ${listOf("1/2", "2", "3/2").random(random)}")
                        appendLine("offset: ${random.nextInt(100, 2_000)}")
                    }
                    DemoTimelineFeature.Gregorian -> appendLine("coordinate: gregorian")
                    DemoTimelineFeature.Julian -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.Gregorian)}")
                        appendLine("coordinate: julian")
                    }
                    DemoTimelineFeature.OffsetCalendar -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.Gregorian)}")
                        appendLine("coordinate:")
                        appendLine("  kind: calendar")
                        appendLine("  calendar: gregorian")
                        appendLine("  numbering:")
                        appendLine("    kind: offset")
                        appendLine("    offset: ${listOf(543, 660).random(random)}")
                        appendLine("    yearZero: ${random.nextBoolean()}")
                    }
                    DemoTimelineFeature.AstronomicalCalendar -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.Gregorian)}")
                        appendLine("coordinate:")
                        appendLine("  kind: calendar")
                        appendLine("  calendar: gregorian")
                        appendLine("  numbering: astronomical")
                    }
                    DemoTimelineFeature.Era -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.Gregorian)}")
                        appendLine("coordinate:")
                        appendLine("  kind: era")
                        appendLine("  periods:")
                        appendLine("    - name: Heisei")
                        appendLine("      aliases: [平成, H]")
                        appendLine("      since: 1989-01-08")
                        appendLine("      firstYear: 1")
                        appendLine("    - name: Reiwa")
                        appendLine("      aliases: [令和, R]")
                        appendLine("      since: 2019-05-01")
                        appendLine("      firstYear: 1")
                    }
                    DemoTimelineFeature.Frame -> {
                        appendLine("coordinate:")
                        appendLine("  kind: frame")
                        appendLine("  start: ${random.nextInt(0, 101)}")
                    }
                    DemoTimelineFeature.NonDropTimecode -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.Frame)}")
                        appendLine("coordinate:")
                        appendLine("  kind: timecode")
                        appendLine("  actualFps: 24")
                        appendLine("  nominalFps: 24")
                        appendLine("  dropFrame: false")
                    }
                    DemoTimelineFeature.DropTimecode -> {
                        appendLine("sameAxisAs: ${timelineIdFor(DemoTimelineFeature.Frame)}")
                        appendLine("coordinate:")
                        appendLine("  kind: timecode")
                        appendLine("  actualFps: 30000/1001")
                        appendLine("  nominalFps: 30")
                        appendLine("  dropFrame: true")
                        appendLine("  wrapHours: 24")
                    }
                    DemoTimelineFeature.Fork -> {
                        val baseTimeline = timelineIdFor(DemoTimelineFeature.BaseNumber)
                        appendDerivedFrom(baseTimeline, "fork", random)
                        appendLine("mapsTo: $baseTimeline")
                    }
                    DemoTimelineFeature.Simulation -> appendDerivedFrom(
                        timelineIdFor(DemoTimelineFeature.BaseNumber),
                        "simulation",
                        random,
                    )
                    DemoTimelineFeature.RecordingWithMappings -> {
                        appendLine("coordinate: frame")
                        appendDerivedFrom(timelineIdFor(DemoTimelineFeature.BaseNumber), "recording", random)
                        appendDemoMappings(timelineIdFor(DemoTimelineFeature.BaseNumber), random)
                    }
                    DemoTimelineFeature.Edit -> {
                        appendLine("coordinate: frame")
                        appendDerivedFrom(timelineIdFor(DemoTimelineFeature.RecordingWithMappings), "edit", random)
                    }
                    DemoTimelineFeature.Resample -> {
                        appendLine("coordinate: frame")
                        appendDerivedFrom(timelineIdFor(DemoTimelineFeature.RecordingWithMappings), "resample", random)
                    }
                    DemoTimelineFeature.Copy -> {
                        appendLine("coordinate: number")
                        appendLine("domain: DemoArchive_${random.nextInt(10, 100)}")
                        appendDerivedFrom(timelineIdFor(DemoTimelineFeature.BaseNumber), "copy", random)
                    }
                    DemoTimelineFeature.Derived ->
                        appendLine("derivedFrom: ${timelineIdFor(DemoTimelineFeature.BaseNumber)}")
                }
                appendLine("---")
                appendLine()
                appendLine("# $id")
                appendLine()
                appendLine("$language Demo feature: ${feature.name}.")
            },
        )
    }

    private fun timelineFeature(index: Int): DemoTimelineFeature {
        if (index < coreTimelineFeatures.size) return coreTimelineFeatures[index]
        val extraFeatures = DemoTimelineFeature.entries.filterNot {
            it == DemoTimelineFeature.RecordingWithMappings
        }
        val mixed = seed.toUInt() * 1_664_525u + index.toUInt() * 1_013_904_223u
        return extraFeatures[(mixed % extraFeatures.size.toUInt()).toInt()]
    }

    private fun timelineIdFor(feature: DemoTimelineFeature): String =
        timelineId(coreTimelineFeatures.indexOf(feature))

    private fun StringBuilder.appendDerivedFrom(
        sourceTimeline: String,
        kind: String,
        random: Random,
    ) {
        appendLine("derivedFrom:")
        appendLine("  timeline: $sourceTimeline")
        appendLine("  kind: $kind")
        appendLine("  sourceAt: ${random.nextInt(0, 10_000)}")
        appendLine("  origin: ${random.nextInt(0, 100)}")
        appendLine("  metadata:")
        appendLine("    generator: graphmd-demo")
        appendLine("    sample: ${random.nextInt(1, 1_000)}")
    }

    private fun StringBuilder.appendDemoMappings(targetTimeline: String, random: Random) {
        val offset = random.nextInt(50, 500)
        val rangeEnd = random.nextInt(1_000, 5_000)
        val segmentEnd = random.nextInt(50, 250)
        val segmentTarget = random.nextInt(500, 1_000)
        val pairSource = random.nextInt(10, 100)
        val pairTarget = random.nextInt(200, 500)
        val mappingId = "DemoMap_${random.nextInt(1_000, 10_000)}"
        appendLine("mapsTo:")
        appendLine("  - $targetTimeline")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    id: $mappingId")
        appendLine("    kind: isomorphism")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    kind: alignment")
        appendLine("    precision: exact")
        appendLine("    scale: 1/30")
        appendLine("    offset: $offset")
        appendLine("    range: { from: 0, to: $rangeEnd }")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    kind: correspondence")
        appendLine("    segments:")
        appendLine("      - source: { from: 0, to: $segmentEnd }")
        appendLine("        target: { from: ${segmentTarget + segmentEnd}, to: $segmentTarget }")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    kind: correspondence")
        appendLine("    pairs:")
        appendLine("      - from: $pairSource")
        appendLine("        to: [$pairTarget, ${pairTarget + random.nextInt(1, 20)}]")
        appendLine("      - from: ${pairSource + 1}")
        appendLine("        to: $pairTarget")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    kind: projection")
        appendLine("    precision:")
        appendLine("      kind: approximate")
        appendLine("      error: 1/10")
        appendLine("    scale: 1/24")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    kind: embedding")
        appendLine("    precision:")
        appendLine("      kind: uncertain")
        appendLine("      error: 1/2")
        appendLine("    scale: 1/25")
        appendLine("  - timeline: $targetTimeline")
        appendLine("    kind: coercion")
        appendLine("    requiredContext:")
        appendLine("      - project")
        appendLine("    provenance:")
        appendLine("      generator: graphmd-demo")
        appendLine("      seed: $seed")
        appendLine("    traits:")
        appendLine("      cardinality: many-to-many")
        appendLine("      totality: partial")
        appendLine("      order: non-monotonic")
        appendLine("      invertibility: non-invertible")
        appendLine("      continuity: discrete")
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
        val documentTimelineIndex = random.nextInt(timelineCount)
        val documentTimeline = timelineId(documentTimelineIndex)
        val documentRange = randomTemporalRange(documentTimelineIndex, random)
        val entityCount = nodeCount + mediaCount
        val linkCount = minOf(3, entityCount - 1)
        val targetIndices = randomTargetIndices(index, entityCount, random)
            .take(random.nextInt(1, linkCount + 1))
        val links = targetIndices.joinToString("\n") { targetIndex ->
            val relType = relTypeId(random.nextInt(relTypeCount))
            val timelineIndex = random.nextInt(timelineCount)
            val timeline = timelineId(timelineIndex)
            val range = randomTemporalRange(timelineIndex, random)
            val weight = random.nextInt(10, 100).toDouble() / 100
            val recordedAt = random.nextInt(1, 10_000)
            val target = entityId(targetIndex)
            "@link(validTime=$timeline(from=${range.from},to=${range.to}))" +
                "{weight=$weight,recordedAt=$recordedAt}" +
                "[${displayName(target)}]($target $relType)"
        }
        val prose = if (index % 2 == 0) {
            """
                $title は${japanesePlaces.random(random)}で記録されました。複数の資料を比較し、背景と変化を短くまとめています。
                概要は @props{summary(validTime=$documentTimeline(from=${documentRange.from},to=${documentRange.to})) = "継続的に更新される記録"} として整理されています。
            """.trimIndent()
        } else {
            """
                $title was recorded near ${englishPlaces.random(random)}. It summarizes the context and recent changes in a few concise observations.
                Its summary is @props{summary(validTime=$documentTimeline(from=${documentRange.from},to=${documentRange.to})) = "A record updated over time"}.
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
                appendLine("    timeline: ${timelineIdFor(DemoTimelineFeature.BaseNumber)}")
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
                appendLine("    from: ${documentRange.from}")
                appendLine("    to: ${documentRange.to}")
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

    private fun randomTemporalRange(timelineIndex: Int, random: Random): DemoTemporalRange =
        when (timelineFeature(timelineIndex)) {
            DemoTimelineFeature.Gregorian,
            DemoTimelineFeature.Julian,
            DemoTimelineFeature.AstronomicalCalendar,
            -> {
                val year = random.nextInt(2020, 2031)
                val month = random.nextInt(1, 13)
                val fromDay = random.nextInt(1, 20)
                val toDay = fromDay + random.nextInt(1, 8)
                DemoTemporalRange(
                    quotedDate(year, month, fromDay),
                    quotedDate(year, month, toDay),
                )
            }
            DemoTimelineFeature.OffsetCalendar -> {
                val year = random.nextInt(2_700, 2_801)
                val month = random.nextInt(1, 13)
                val fromDay = random.nextInt(1, 20)
                val toDay = fromDay + random.nextInt(1, 8)
                DemoTemporalRange(
                    quotedDate(year, month, fromDay),
                    quotedDate(year, month, toDay),
                )
            }
            DemoTimelineFeature.Era -> {
                val year = random.nextInt(2, 10)
                val month = random.nextInt(1, 13)
                val fromDay = random.nextInt(1, 20)
                val toDay = fromDay + random.nextInt(1, 8)
                DemoTemporalRange(
                    "\"令和 $year-${month.twoDigits()}-${fromDay.twoDigits()}\"",
                    "\"令和 $year-${month.twoDigits()}-${toDay.twoDigits()}\"",
                )
            }
            DemoTimelineFeature.NonDropTimecode,
            DemoTimelineFeature.DropTimecode,
            -> {
                val dropFrame = timelineFeature(timelineIndex) == DemoTimelineFeature.DropTimecode
                val nominalFps = if (dropFrame) 30 else 24
                val hour = random.nextInt(0, 23)
                val minute = random.nextInt(0, 60)
                val fromSecond = random.nextInt(5, 45)
                val toSecond = fromSecond + random.nextInt(1, 10)
                val frame = random.nextInt(0, nominalFps)
                val separator = if (dropFrame) ";" else ":"
                fun timecode(second: Int): String =
                    "\"${hour.twoDigits()}:${minute.twoDigits()}:${second.twoDigits()}$separator${frame.twoDigits()}\""
                DemoTemporalRange(timecode(fromSecond), timecode(toSecond))
            }
            else -> {
                val from = random.nextInt(0, 9_000)
                DemoTemporalRange(from.toString(), (from + random.nextInt(10, 1_000)).toString())
            }
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
        val total = max(requestedCount, minimumDemoDocumentCount)
        val timelineCount = max(DemoTimelineFeature.entries.size, total / 50)
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

private val minimumDemoDocumentCount: Int = DemoTimelineFeature.entries.size + 6

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
private fun Int.twoDigits(): String = toString().padStart(2, '0')
private fun quotedDate(year: Int, month: Int, day: Int): String =
    "\"$year-${month.twoDigits()}-${day.twoDigits()}\""
private fun displayName(id: String): String = id.replace('_', ' ')
private fun yamlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

private val japaneseAdjectives = listOf("静かな", "新しい", "歴史的な", "小さな", "重要な")
private val japaneseNouns = listOf("研究計画", "地域資料", "観測記録", "文化活動", "共同調査")
private val japanesePlaces = listOf("東京", "札幌", "京都", "福岡", "瀬戸内")
private val englishAdjectives = listOf("Quiet", "Emerging", "Historic", "Regional", "Collaborative")
private val englishNouns = listOf("Research Project", "Field Record", "Archive", "Survey", "Study")
private val englishPlaces = listOf("Tokyo", "Sapporo", "Kyoto", "Fukuoka", "the Seto Inland Sea")
