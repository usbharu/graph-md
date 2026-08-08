package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

class GraphDocumentParser {
    fun parseDocument(text: String, sourcePath: String): ParsedGraphDocumentResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val split = splitFrontMatter(text, sourcePath, diagnostics) ?: return ParsedGraphDocumentResult(null, diagnostics)
        val root = MiniYamlParser(split.frontMatter, sourcePath, diagnostics).parse() ?: return ParsedGraphDocumentResult(null, diagnostics)
        val document = toGraphDocument(root, split.body, sourcePath, diagnostics)
        return ParsedGraphDocumentResult(document, diagnostics)
    }

    private fun splitFrontMatter(
        text: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): FrontMatterSplit? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        if (lines.firstOrNull() != "---") {
            diagnostics += syntaxError("Document MUST start with YAML front matter", sourcePath)
            return null
        }
        val relativeEndIndex = lines.drop(1).indexOfFirst { it == "---" || it == "..." }
        val endIndex = relativeEndIndex.takeIf { it >= 0 }?.plus(1)
        if (endIndex == null) {
            diagnostics += syntaxError("Unclosed YAML front matter", sourcePath)
            return null
        }
        return FrontMatterSplit(
            frontMatter = lines.subList(1, endIndex).joinToString("\n"),
            body = lines.drop(endIndex + 1).joinToString("\n"),
        )
    }

    private fun toGraphDocument(
        value: YamlValue,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument? {
        val root = value as? YamlMap ?: run {
            diagnostics += schemaError("Front matter root MUST be a mapping", sourcePath)
            return null
        }
        val id = root.requireString("id", sourcePath, diagnostics) ?: return null
        if (id.isEmpty()) {
            diagnostics += schemaError("id MUST be non-empty", sourcePath)
            return null
        }
        val kindName = root.requireString("kind", sourcePath, diagnostics)
        if (kindName == "RelType" && id.any { it.isWhitespace() }) {
            diagnostics += schemaError("RelType id MUST NOT contain whitespace", sourcePath, id)
            return null
        }
        if (!id.matches(Regex("""[A-Za-z_][A-Za-z0-9_.:-]*"""))) {
            diagnostics += schemaWarning(
                "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*",
                sourcePath,
                id,
            )
        }
        if (kindName == null) return null
        return when (kindName) {
            "Node" -> parseNodeDocument(id, root, body, sourcePath, diagnostics, media = false)
            "Media" -> parseNodeDocument(id, root, body, sourcePath, diagnostics, media = true)
            "NodeType" -> parseNodeTypeDocument(id, root, body, sourcePath, diagnostics)
            "RelType" -> parseRelTypeDocument(id, root, body, sourcePath, diagnostics)
            "Timeline" -> parseTimelineDocument(id, root, body, sourcePath, diagnostics)
            else -> {
                diagnostics += schemaError("Unknown document kind: $kindName", sourcePath, id)
                null
            }
        }
    }

    private fun parseNodeDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        media: Boolean,
    ): GraphDocument? {
        val type = root.requireString("type", sourcePath, diagnostics, id) ?: return null
        val url = root.string("url", sourcePath, diagnostics, id)
        if (media && url == null) {
            diagnostics += schemaError("Media requires url", sourcePath, id)
            return null
        }
        return NodeDocument(
            id = id,
            type = type,
            props = root.map["props"]?.let { parseRawObjectEntries(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            url = url,
            validTime = root.map["validTime"]?.let { parseValidTimes(it, sourcePath, diagnostics, id) } ?: emptyList(),
            body = body,
            sourcePath = sourcePath,
            topLevelFields = root.map.keys,
            documentKind = if (media) DocumentKind.Media else DocumentKind.Node,
        )
    }

    private fun parseValidTimes(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): List<ValidTime> {
        val entries = value as? YamlList ?: run {
            diagnostics += schemaError("validTime MUST be a non-empty list", sourcePath, documentId)
            return emptyList()
        }
        if (entries.values.isEmpty()) {
            diagnostics += schemaError("validTime MUST be a non-empty list", sourcePath, documentId)
        }
        return entries.values.mapNotNull { entry ->
            val map = entry as? YamlMap ?: run {
                diagnostics += schemaError("validTime entries MUST be mappings", sourcePath, documentId)
                return@mapNotNull null
            }
            val timeline = map.requireString("timeline", sourcePath, diagnostics, documentId) ?: return@mapNotNull null
            if (timeline.isEmpty()) {
                diagnostics += schemaError("validTime.timeline MUST be non-empty", sourcePath, documentId)
                return@mapNotNull null
            }
            val unknown = map.map.keys - setOf("timeline", "from", "to")
            unknown.forEach { diagnostics += schemaError("Unknown validTime field: $it", sourcePath, documentId) }
            ValidTime(
                timeline = timeline,
                from = map.map["from"]?.let { parseTimePoint(it, "validTime.from", sourcePath, diagnostics, documentId) },
                to = map.map["to"]?.let { parseTimePoint(it, "validTime.to", sourcePath, diagnostics, documentId) },
            )
        }
    }

    private fun parseTimePoint(
        value: YamlValue,
        field: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TimePoint? {
        parseTemporalCoordinate(value, sourcePath, diagnostics, documentId, field)?.let { return TimePoint(it) }
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$field MUST be a temporal coordinate", sourcePath, documentId)
            return null
        }
        val legacy = map.map["timecode"]
        if (legacy != null) {
            diagnostics += schemaError("$field.timecode was removed; write the coordinate directly", sourcePath, documentId)
            val coordinate = parseTemporalCoordinate(legacy, sourcePath, diagnostics, documentId, "$field.timecode") ?: return null
            return TimePoint(coordinate, map.string("value", sourcePath, diagnostics, documentId))
        }
        diagnostics += schemaError("$field MUST be a temporal coordinate", sourcePath, documentId)
        return null
    }

    private fun parseNodeTypeDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument {
        validateTopLevelFields(root, setOf("id", "kind", "extends", "props"), sourcePath, diagnostics, id)
        return NodeTypeDocument(
            id = id,
            extends = root.stringList("extends", sourcePath, diagnostics, id) ?: emptyList(),
            props = root.map["props"]?.let { parsePropSchemaMap(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
        )
    }

    private fun parseRelTypeDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument {
        validateTopLevelFields(root, setOf("id", "kind", "extends", "from", "to", "props"), sourcePath, diagnostics, id)
        return RelTypeDocument(
            id = id,
            extends = root.stringList("extends", sourcePath, diagnostics, id) ?: emptyList(),
            from = root.stringList("from", sourcePath, diagnostics, id),
            to = root.stringList("to", sourcePath, diagnostics, id),
            props = root.map["props"]?.let { parsePropSchemaMap(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
        )
    }

    private fun validateTopLevelFields(
        root: YamlMap,
        allowed: Set<String>,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ) {
        (root.map.keys - allowed).forEach {
            diagnostics += schemaError("Unknown top-level field: $it", sourcePath, documentId)
        }
    }

    private fun parseTimelineDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument {
        val allowedFields = setOf(
            "id", "kind", "sameAxisAs", "scale", "offset", "coordinate", "domain",
            "derivedFrom", "mapsTo", "aliases", "props", "extends", "timecode", "mappings",
        )
        root.map.keys.filterNot { it in allowedFields }.forEach { field ->
            diagnostics += schemaError("Unknown top-level field: $field", sourcePath, id)
        }
        val legacyFields = root.map.keys.intersect(setOf("extends", "timecode", "mappings"))
        legacyFields.forEach { field ->
            val replacement = when (field) {
                "extends" -> "sameAxisAs or derivedFrom"
                "timecode" -> "coordinate"
                else -> "mapsTo"
            }
            diagnostics += schemaError("Timeline.$field was removed; use $replacement", sourcePath, id)
        }
        val sameAxisAs = root.string("sameAxisAs", sourcePath, diagnostics, id)
        val derivedFrom = root.map["derivedFrom"]?.let {
            parseDerivedFrom(it, sourcePath, diagnostics, id)
        }
        if (sameAxisAs != null && derivedFrom != null) {
            diagnostics += schemaError("sameAxisAs and derivedFrom are mutually exclusive", sourcePath, id)
        }
        val scale = root.map["scale"]?.let {
            parseExactRational(it, sourcePath, diagnostics, id, "scale")
        } ?: ExactRational.ONE
        if (scale == ExactRational.ZERO) diagnostics += schemaError("scale MUST NOT be zero", sourcePath, id)
        val offset = root.map["offset"]?.let {
            parseExactRational(it, sourcePath, diagnostics, id, "offset")
        } ?: ExactRational.ZERO
        if (("scale" in root.map || "offset" in root.map) && sameAxisAs == null) {
            diagnostics += schemaError("scale and offset require sameAxisAs", sourcePath, id)
        }
        val coordinate = root.map["coordinate"]?.let {
            parseCoordinateSpec(it, sourcePath, diagnostics, id)
        }
        val mapsTo = root.map["mapsTo"]?.let {
            parseTemporalMappings(it, sourcePath, diagnostics, id)
        }.orEmpty()
        val mappings = when (val rawMappings = root.map["mappings"]?.takeUnless { it == YamlNull }) {
            null -> emptyList()
            is YamlList -> rawMappings.values.mapNotNull { parseTimelineMapping(it, sourcePath, diagnostics, id) }
            else -> {
                diagnostics += schemaError("mappings MUST be a list", sourcePath, id)
                emptyList()
            }
        }
        if (mappings.isNotEmpty() && root.map["timecode"] == null) {
            diagnostics += schemaError("Timeline with mappings requires timecode", sourcePath, id)
        }
        root.map["props"]?.let { validateTimelineProps(it, sourcePath, diagnostics, id) }
        return TimelineDocument(
            id = id,
            extends = root.stringList("extends", sourcePath, diagnostics, id) ?: emptyList(),
            timecode = root.map["timecode"]?.takeUnless { it == YamlNull }?.let { parseTimecodeSchema(it, sourcePath, diagnostics, id) },
            mappings = mappings,
            props = root.map["props"]?.let { parseRawObjectEntries(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
            sameAxisAs = sameAxisAs,
            scale = scale,
            offset = offset,
            coordinate = coordinate,
            domain = root.string("domain", sourcePath, diagnostics, id),
            derivedFrom = derivedFrom,
            mapsTo = mapsTo,
            aliases = root.stringList("aliases", sourcePath, diagnostics, id) ?: emptyList(),
            usesLegacyTimelineSyntax = legacyFields.isNotEmpty(),
        )
    }

    private fun parseCoordinateSpec(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalCoordinateSpec? {
        if (value is YamlString) {
            return when (value.value) {
                "number" -> TemporalCoordinateSpec.Number
                "gregorian" -> TemporalCoordinateSpec.Calendar(CalendarKind.Gregorian)
                "julian" -> TemporalCoordinateSpec.Calendar(CalendarKind.Julian)
                "frame" -> TemporalCoordinateSpec.Frame()
                else -> {
                    diagnostics += schemaError("Unknown coordinate preset: ${value.value}", sourcePath, documentId)
                    null
                }
            }
        }
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("coordinate MUST be a preset or mapping", sourcePath, documentId)
            return null
        }
        return when (val kind = map.requireString("kind", sourcePath, diagnostics, documentId)) {
            "number" -> {
                reportUnknownFields(map, setOf("kind", "unit"), "coordinate", sourcePath, diagnostics, documentId)
                TemporalCoordinateSpec.Number
            }
            "calendar" -> {
                reportUnknownFields(map, setOf("kind", "calendar", "numbering"), "coordinate", sourcePath, diagnostics, documentId)
                val calendar = when (map.string("calendar", sourcePath, diagnostics, documentId) ?: "gregorian") {
                    "gregorian" -> CalendarKind.Gregorian
                    "julian" -> CalendarKind.Julian
                    else -> {
                        diagnostics += schemaError("coordinate.calendar MUST be gregorian or julian", sourcePath, documentId)
                        return null
                    }
                }
                val numbering = map.map["numbering"]?.let {
                    parseYearNumbering(it, sourcePath, diagnostics, documentId)
                } ?: YearNumbering.CommonEra
                TemporalCoordinateSpec.Calendar(calendar, numbering)
            }
            "calendar-pattern" -> parseCalendarPatternSpec(map, sourcePath, diagnostics, documentId)
            "frame" -> {
                reportUnknownFields(map, setOf("kind", "start"), "coordinate", sourcePath, diagnostics, documentId)
                TemporalCoordinateSpec.Frame(map.long("start", sourcePath, diagnostics, documentId) ?: 0)
            }
            "timecode" -> {
                reportUnknownFields(
                    map,
                    setOf("kind", "actualFps", "nominalFps", "dropFrame", "wrapHours"),
                    "coordinate",
                    sourcePath,
                    diagnostics,
                    documentId,
                )
                val fpsValue = map.map["actualFps"] ?: run {
                    diagnostics += schemaError("coordinate.actualFps is required", sourcePath, documentId)
                    return null
                }
                val actualFps = parseExactRational(fpsValue, sourcePath, diagnostics, documentId, "coordinate.actualFps")
                    ?: return null
                val nominalFps = map.long("nominalFps", sourcePath, diagnostics, documentId)?.toInt() ?: run {
                    diagnostics += schemaError("coordinate.nominalFps is required", sourcePath, documentId)
                    return null
                }
                val dropFrame = map.boolean("dropFrame", sourcePath, diagnostics, documentId) ?: false
                val wrapHours = map.long("wrapHours", sourcePath, diagnostics, documentId)?.toInt()
                if (actualFps <= ExactRational.ZERO || nominalFps <= 0) {
                    diagnostics += schemaError("timecode frame rates MUST be positive", sourcePath, documentId)
                    return null
                }
                if (dropFrame && nominalFps !in setOf(30, 60)) {
                    diagnostics += schemaError("dropFrame requires nominalFps 30 or 60", sourcePath, documentId)
                }
                if (wrapHours != null && wrapHours <= 0) {
                    diagnostics += schemaError("coordinate.wrapHours MUST be positive", sourcePath, documentId)
                }
                TemporalCoordinateSpec.Timecode(actualFps, nominalFps, dropFrame, wrapHours)
            }
            "era" -> {
                reportUnknownFields(map, setOf("kind", "periods"), "coordinate", sourcePath, diagnostics, documentId)
                val periodsValue = map.map["periods"] as? YamlList ?: run {
                    diagnostics += schemaError("coordinate.periods MUST be a non-empty list", sourcePath, documentId)
                    return null
                }
                val periods = periodsValue.values.mapNotNull {
                    parseEraPeriod(it, sourcePath, diagnostics, documentId)
                }
                if (periods.isEmpty()) diagnostics += schemaError("coordinate.periods MUST be non-empty", sourcePath, documentId)
                TemporalCoordinateSpec.Era(periods)
            }
            null -> null
            else -> {
                diagnostics += schemaError("Unknown coordinate kind: $kind", sourcePath, documentId)
                null
            }
        }
    }

    private fun parseCalendarPatternSpec(
        map: YamlMap,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalCoordinateSpec.CalendarPattern? {
        reportUnknownFields(
            map,
            setOf(
                "kind", "calendar", "numbering", "fields", "granularity", "repeatsEvery", "format",
                "quarterStartMonth", "quarterYearLabel",
            ),
            "coordinate",
            sourcePath,
            diagnostics,
            documentId,
        )
        val calendar = when (map.string("calendar", sourcePath, diagnostics, documentId) ?: "gregorian") {
            "gregorian" -> CalendarKind.Gregorian
            "julian" -> CalendarKind.Julian
            else -> {
                diagnostics += schemaError("coordinate.calendar MUST be gregorian or julian", sourcePath, documentId)
                return null
            }
        }
        val numbering = map.map["numbering"]?.let {
            parseYearNumbering(it, sourcePath, diagnostics, documentId)
        } ?: YearNumbering.CommonEra
        val rawFields = map.stringList("fields", sourcePath, diagnostics, documentId) ?: run {
            diagnostics += schemaError("coordinate.fields MUST be a non-empty list", sourcePath, documentId)
            return null
        }
        val fields = rawFields.mapNotNull { raw ->
            when (raw) {
                "year" -> CalendarField.Year
                "month" -> CalendarField.Month
                "day" -> CalendarField.Day
                "quarter" -> CalendarField.Quarter
                "weekYear" -> CalendarField.WeekYear
                "week" -> CalendarField.Week
                else -> {
                    diagnostics += schemaError("Unknown calendar-pattern field: $raw", sourcePath, documentId)
                    null
                }
            }
        }
        if (fields.isEmpty()) {
            diagnostics += schemaError("coordinate.fields MUST be a non-empty list", sourcePath, documentId)
            return null
        }
        if (fields.size != fields.distinct().size) {
            diagnostics += schemaError("coordinate.fields MUST NOT contain duplicates", sourcePath, documentId)
        }
        val inferredGranularity = when {
            CalendarField.Day in fields -> CalendarGranularity.Day
            CalendarField.Week in fields -> CalendarGranularity.Week
            CalendarField.Month in fields -> CalendarGranularity.Month
            CalendarField.Quarter in fields -> CalendarGranularity.Quarter
            else -> CalendarGranularity.Year
        }
        val granularity = when (val raw = map.string("granularity", sourcePath, diagnostics, documentId)) {
            null -> inferredGranularity
            "day" -> CalendarGranularity.Day
            "week" -> CalendarGranularity.Week
            "month" -> CalendarGranularity.Month
            "quarter" -> CalendarGranularity.Quarter
            "year" -> CalendarGranularity.Year
            else -> {
                diagnostics += schemaError("Unknown calendar-pattern granularity: $raw", sourcePath, documentId)
                inferredGranularity
            }
        }
        if (granularity != inferredGranularity) {
            diagnostics += schemaError(
                "coordinate.granularity MUST match the least-significant declared field (${inferredGranularity.name.lowercase()})",
                sourcePath,
                documentId,
            )
        }
        val repeatsEvery = when (val raw = map.string("repeatsEvery", sourcePath, diagnostics, documentId)) {
            null -> null
            "year" -> CalendarRepeat.Year
            else -> {
                diagnostics += schemaError("Unknown calendar-pattern repeat period: $raw", sourcePath, documentId)
                null
            }
        }
        val fieldSet = fields.toSet()
        val iso = fieldSet.intersect(setOf(CalendarField.WeekYear, CalendarField.Week))
        val ordinary = fieldSet.intersect(setOf(CalendarField.Year, CalendarField.Month, CalendarField.Day, CalendarField.Quarter))
        if (iso.isNotEmpty() && ordinary.isNotEmpty()) {
            diagnostics += schemaError("ISO week fields cannot be mixed with calendar or quarter fields", sourcePath, documentId)
        }
        if (CalendarField.Day in fieldSet && CalendarField.Month !in fieldSet) {
            diagnostics += schemaError("calendar-pattern day requires month", sourcePath, documentId)
        }
        if (CalendarField.Quarter in fieldSet && fieldSet.any { it == CalendarField.Month || it == CalendarField.Day }) {
            diagnostics += schemaError("calendar-pattern quarter cannot be mixed with month or day", sourcePath, documentId)
        }
        if (iso.isNotEmpty() && calendar != CalendarKind.Gregorian) {
            diagnostics += schemaError("ISO week fields require the Gregorian calendar", sourcePath, documentId)
        }
        val hasAuthoredYear = CalendarField.Year in fieldSet || CalendarField.WeekYear in fieldSet
        if (!hasAuthoredYear && repeatsEvery == null) {
            diagnostics += schemaError("calendar-pattern fields without a year require repeatsEvery", sourcePath, documentId)
        }
        if (hasAuthoredYear && repeatsEvery != null) {
            diagnostics += schemaError("repeating calendar-pattern fields MUST omit year and weekYear", sourcePath, documentId)
        }
        val quarterStartMonth = map.long("quarterStartMonth", sourcePath, diagnostics, documentId)?.toInt() ?: 1
        if (quarterStartMonth !in 1..12) {
            diagnostics += schemaError("coordinate.quarterStartMonth MUST be between 1 and 12", sourcePath, documentId)
        }
        if (("quarterStartMonth" in map.map || "quarterYearLabel" in map.map) && CalendarField.Quarter !in fieldSet) {
            diagnostics += schemaError("quarter options require the quarter field", sourcePath, documentId)
        }
        val quarterYearLabel = when (val raw = map.string("quarterYearLabel", sourcePath, diagnostics, documentId)) {
            null, "start" -> QuarterYearLabel.Start
            "end" -> QuarterYearLabel.End
            else -> {
                diagnostics += schemaError("coordinate.quarterYearLabel MUST be start or end", sourcePath, documentId)
                QuarterYearLabel.Start
            }
        }
        val format = map.string("format", sourcePath, diagnostics, documentId)
        format?.let { validateCalendarPatternFormat(it, fieldSet, sourcePath, diagnostics, documentId) }
        return TemporalCoordinateSpec.CalendarPattern(
            calendar = calendar,
            fields = fields.distinct().sortedBy(CalendarField::ordinal),
            numbering = numbering,
            granularity = granularity,
            repeatsEvery = repeatsEvery,
            format = format,
            quarterStartMonth = quarterStartMonth,
            quarterYearLabel = quarterYearLabel,
        )
    }

    private fun validateCalendarPatternFormat(
        format: String,
        fields: Set<CalendarField>,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ) {
        val placeholders = Regex("""\{([A-Za-z][A-Za-z0-9]*)(?::(\d+))?\}""").findAll(format).toList()
        val names = placeholders.map { it.groupValues[1] }
        val expected = fields.map {
            when (it) {
                CalendarField.Year -> "year"
                CalendarField.Month -> "month"
                CalendarField.Day -> "day"
                CalendarField.Quarter -> "quarter"
                CalendarField.WeekYear -> "weekYear"
                CalendarField.Week -> "week"
            }
        }
        if (names.toSet() != expected.toSet() || names.size != expected.size) {
            diagnostics += schemaError(
                "coordinate.format MUST reference every declared field exactly once",
                sourcePath,
                documentId,
            )
        }
        if (format.replace(Regex("""\{[A-Za-z][A-Za-z0-9]*(?::\d+)?\}"""), "").contains('{') ||
            format.replace(Regex("""\{[A-Za-z][A-Za-z0-9]*(?::\d+)?\}"""), "").contains('}')
        ) {
            diagnostics += schemaError("coordinate.format contains an invalid placeholder", sourcePath, documentId)
        }
    }

    private fun parseYearNumbering(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): YearNumbering? {
        if (value is YamlString) return when (value.value) {
            "common-era" -> YearNumbering.CommonEra
            "astronomical" -> YearNumbering.Astronomical
            else -> {
                diagnostics += schemaError("Unknown year numbering: ${value.value}", sourcePath, documentId)
                null
            }
        }
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("coordinate.numbering MUST be a string or mapping", sourcePath, documentId)
            return null
        }
        reportUnknownFields(map, setOf("kind", "offset", "yearZero"), "coordinate.numbering", sourcePath, diagnostics, documentId)
        return when (map.requireString("kind", sourcePath, diagnostics, documentId)) {
            "common-era" -> YearNumbering.CommonEra
            "astronomical" -> YearNumbering.Astronomical
            "offset" -> YearNumbering.Offset(
                map.long("offset", sourcePath, diagnostics, documentId) ?: run {
                    diagnostics += schemaError("coordinate.numbering.offset is required", sourcePath, documentId)
                    return null
                },
                map.boolean("yearZero", sourcePath, diagnostics, documentId) ?: false,
            )
            null -> null
            else -> {
                diagnostics += schemaError("Unknown coordinate.numbering kind", sourcePath, documentId)
                null
            }
        }
    }

    private fun parseEraPeriod(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): EraPeriodSpec? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("coordinate.periods entries MUST be mappings", sourcePath, documentId)
            return null
        }
        reportUnknownFields(map, setOf("name", "aliases", "since", "firstYear"), "coordinate.period", sourcePath, diagnostics, documentId)
        val name = map.requireString("name", sourcePath, diagnostics, documentId) ?: return null
        val since = map.map["since"]?.let { temporalCoordinateText(it) } ?: run {
            diagnostics += schemaError("coordinate.period.since is required", sourcePath, documentId)
            return null
        }
        return EraPeriodSpec(
            name,
            map.stringList("aliases", sourcePath, diagnostics, documentId) ?: emptyList(),
            since,
            map.long("firstYear", sourcePath, diagnostics, documentId) ?: 1,
        )
    }

    private fun parseDerivedFrom(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): DerivedFromSpec? {
        if (value is YamlString) return DerivedFromSpec(value.value)
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("derivedFrom MUST be a Timeline id or mapping", sourcePath, documentId)
            return null
        }
        reportUnknownFields(
            map,
            setOf("timeline", "kind", "sourceAt", "origin", "metadata"),
            "derivedFrom",
            sourcePath,
            diagnostics,
            documentId,
        )
        val timeline = map.requireString("timeline", sourcePath, diagnostics, documentId) ?: return null
        val kind = when (map.string("kind", sourcePath, diagnostics, documentId) ?: "derived") {
            "fork" -> AxisLineageKind.Fork
            "simulation" -> AxisLineageKind.Simulation
            "recording" -> AxisLineageKind.Recording
            "edit" -> AxisLineageKind.Edit
            "resample" -> AxisLineageKind.Resample
            "copy" -> AxisLineageKind.Copy
            "derived" -> AxisLineageKind.Derived
            else -> {
                diagnostics += schemaError("Unknown derivedFrom.kind", sourcePath, documentId)
                AxisLineageKind.Derived
            }
        }
        return DerivedFromSpec(
            timeline,
            kind,
            map.map["sourceAt"]?.let { parseTemporalCoordinate(it, sourcePath, diagnostics, documentId, "derivedFrom.sourceAt") },
            map.map["origin"]?.let { parseTemporalCoordinate(it, sourcePath, diagnostics, documentId, "derivedFrom.origin") },
            map.map["metadata"]?.let { parseRawObjectEntries(it, sourcePath, diagnostics, documentId, "derivedFrom.metadata") }
                ?: emptyMap(),
        )
    }

    private fun parseTemporalMappings(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): List<TemporalMappingSpec> = when (value) {
        is YamlString -> listOf(TemporalMappingSpec(timeline = value.value))
        is YamlMap -> listOfNotNull(parseTemporalMappingSpec(value, sourcePath, diagnostics, documentId))
        is YamlList -> value.values.mapNotNull {
            when (it) {
                is YamlString -> TemporalMappingSpec(timeline = it.value)
                else -> parseTemporalMappingSpec(it, sourcePath, diagnostics, documentId)
            }
        }
        else -> {
            diagnostics += schemaError("mapsTo MUST be a Timeline id, mapping, or list", sourcePath, documentId)
            emptyList()
        }
    }

    private fun parseTemporalMappingSpec(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalMappingSpec? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("mapsTo entries MUST be mappings", sourcePath, documentId)
            return null
        }
        reportUnknownFields(
            map,
            setOf(
                "id", "timeline", "kind", "precision", "scale", "offset", "range", "segments", "pairs",
                "traits", "requiredContext", "provenance",
            ),
            "mapsTo",
            sourcePath,
            diagnostics,
            documentId,
        )
        val timeline = map.requireString("timeline", sourcePath, diagnostics, documentId) ?: return null
        val scale = map.map["scale"]?.let { parseExactRational(it, sourcePath, diagnostics, documentId, "mapsTo.scale") }
            ?: ExactRational.ONE
        val offset = map.map["offset"]?.let { parseExactRational(it, sourcePath, diagnostics, documentId, "mapsTo.offset") }
            ?: ExactRational.ZERO
        return TemporalMappingSpec(
            id = map.string("id", sourcePath, diagnostics, documentId),
            timeline = timeline,
            kind = parseMappingKind(map.string("kind", sourcePath, diagnostics, documentId), sourcePath, diagnostics, documentId),
            precision = map.map["precision"]?.let { parsePrecision(it, sourcePath, diagnostics, documentId) }
                ?: TemporalPrecision(),
            scale = scale,
            offset = offset,
            range = map.map["range"]?.let { parseCoordinateRange(it, sourcePath, diagnostics, documentId, "mapsTo.range") },
            segments = map.map["segments"]?.let { parseMappingSegments(it, sourcePath, diagnostics, documentId) }.orEmpty(),
            pairs = map.map["pairs"]?.let { parseMappingPairs(it, sourcePath, diagnostics, documentId, "mapsTo.pairs") }.orEmpty(),
            traits = map.map["traits"]?.let { parseTraits(it, sourcePath, diagnostics, documentId) },
            requiredContext = map.stringList("requiredContext", sourcePath, diagnostics, documentId) ?: emptyList(),
            provenance = map.map["provenance"]?.let { parseRawObjectEntries(it, sourcePath, diagnostics, documentId, "mapsTo.provenance") }
                ?: emptyMap(),
        )
    }

    private fun parseMappingKind(
        value: String?,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalMappingKind = when (value ?: "isomorphism") {
        "coercion" -> TemporalMappingKind.Coercion
        "isomorphism" -> TemporalMappingKind.Isomorphism
        "embedding" -> TemporalMappingKind.Embedding
        "projection" -> TemporalMappingKind.Projection
        "alignment" -> TemporalMappingKind.Alignment
        "correspondence" -> TemporalMappingKind.Correspondence
        else -> {
            diagnostics += schemaError("Unknown mapsTo.kind: $value", sourcePath, documentId)
            TemporalMappingKind.Correspondence
        }
    }

    private fun parsePrecision(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalPrecision? {
        if (value is YamlString) return TemporalPrecision(parsePrecisionKind(value.value, sourcePath, diagnostics, documentId))
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("mapsTo.precision MUST be a string or mapping", sourcePath, documentId)
            return null
        }
        reportUnknownFields(map, setOf("kind", "error"), "mapsTo.precision", sourcePath, diagnostics, documentId)
        val kind = parsePrecisionKind(map.requireString("kind", sourcePath, diagnostics, documentId), sourcePath, diagnostics, documentId)
        val error = map.map["error"]?.let { parseExactRational(it, sourcePath, diagnostics, documentId, "mapsTo.precision.error") }
        if (kind == TemporalPrecisionKind.Approximate && error == null) {
            diagnostics += schemaError("approximate precision requires error", sourcePath, documentId)
        }
        return TemporalPrecision(kind, error)
    }

    private fun parsePrecisionKind(
        value: String?,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalPrecisionKind = when (value) {
        "exact" -> TemporalPrecisionKind.Exact
        "approximate" -> TemporalPrecisionKind.Approximate
        "uncertain" -> TemporalPrecisionKind.Uncertain
        else -> {
            diagnostics += schemaError("Unknown precision: $value", sourcePath, documentId)
            TemporalPrecisionKind.Uncertain
        }
    }

    private fun parseCoordinateRange(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        field: String,
    ): TemporalCoordinateRange? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$field MUST be a mapping", sourcePath, documentId)
            return null
        }
        reportUnknownFields(map, setOf("from", "to"), field, sourcePath, diagnostics, documentId)
        return TemporalCoordinateRange(
            map.map["from"]?.let { parseTemporalCoordinate(it, sourcePath, diagnostics, documentId, "$field.from") },
            map.map["to"]?.let { parseTemporalCoordinate(it, sourcePath, diagnostics, documentId, "$field.to") },
        )
    }

    private fun parseMappingSegments(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): List<TemporalMappingSegment> {
        val list = value as? YamlList ?: run {
            diagnostics += schemaError("mapsTo.segments MUST be a list", sourcePath, documentId)
            return emptyList()
        }
        return list.values.mapNotNull { segmentValue ->
            val map = segmentValue as? YamlMap ?: run {
                diagnostics += schemaError("mapsTo.segments entries MUST be mappings", sourcePath, documentId)
                return@mapNotNull null
            }
            reportUnknownFields(map, setOf("source", "target", "scale", "offset", "pairs"), "mapsTo.segment", sourcePath, diagnostics, documentId)
            TemporalMappingSegment(
                source = map.map["source"]?.let { parseCoordinateRange(it, sourcePath, diagnostics, documentId, "mapsTo.segment.source") },
                target = map.map["target"]?.let { parseCoordinateRange(it, sourcePath, diagnostics, documentId, "mapsTo.segment.target") },
                scale = map.map["scale"]?.let { parseExactRational(it, sourcePath, diagnostics, documentId, "mapsTo.segment.scale") }
                    ?: ExactRational.ONE,
                offset = map.map["offset"]?.let { parseExactRational(it, sourcePath, diagnostics, documentId, "mapsTo.segment.offset") }
                    ?: ExactRational.ZERO,
                pairs = map.map["pairs"]?.let { parseMappingPairs(it, sourcePath, diagnostics, documentId, "mapsTo.segment.pairs") }
                    .orEmpty(),
            )
        }
    }

    private fun parseMappingPairs(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        field: String,
    ): List<TemporalMappingPair> {
        val list = value as? YamlList ?: run {
            diagnostics += schemaError("$field MUST be a list", sourcePath, documentId)
            return emptyList()
        }
        return list.values.mapNotNull { pairValue ->
            val map = pairValue as? YamlMap ?: run {
                diagnostics += schemaError("$field entries MUST be mappings", sourcePath, documentId)
                return@mapNotNull null
            }
            reportUnknownFields(map, setOf("from", "to"), field, sourcePath, diagnostics, documentId)
            val from = map.map["from"]?.let { parseTemporalCoordinate(it, sourcePath, diagnostics, documentId, "$field.from") }
                ?: return@mapNotNull null
            val toValue = map.map["to"] ?: run {
                diagnostics += schemaError("$field.to is required", sourcePath, documentId)
                return@mapNotNull null
            }
            val targets = if (toValue is YamlList) {
                toValue.values.mapNotNull { parseTemporalCoordinate(it, sourcePath, diagnostics, documentId, "$field.to") }
            } else {
                listOfNotNull(parseTemporalCoordinate(toValue, sourcePath, diagnostics, documentId, "$field.to"))
            }
            TemporalMappingPair(from, targets)
        }
    }

    private fun parseTraits(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TemporalMappingTraitsOverride? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("mapsTo.traits MUST be a mapping", sourcePath, documentId)
            return null
        }
        reportUnknownFields(
            map,
            setOf("cardinality", "totality", "order", "invertibility", "continuity"),
            "mapsTo.traits",
            sourcePath,
            diagnostics,
            documentId,
        )
        fun string(field: String): String? = map.string(field, sourcePath, diagnostics, documentId)
        return TemporalMappingTraitsOverride(
            cardinality = when (val entry = string("cardinality")) {
                null -> null
                "one-to-one" -> TemporalCardinality.OneToOne
                "one-to-many" -> TemporalCardinality.OneToMany
                "many-to-one" -> TemporalCardinality.ManyToOne
                "many-to-many" -> TemporalCardinality.ManyToMany
                else -> null.also { diagnostics += schemaError("Unknown traits.cardinality: $entry", sourcePath, documentId) }
            },
            totality = when (val entry = string("totality")) {
                null -> null
                "total" -> TemporalTotality.Total
                "partial" -> TemporalTotality.Partial
                else -> null.also { diagnostics += schemaError("Unknown traits.totality: $entry", sourcePath, documentId) }
            },
            orderBehavior = when (val entry = string("order")) {
                null -> null
                "strictly-increasing" -> TemporalOrderBehavior.StrictlyIncreasing
                "strictly-decreasing" -> TemporalOrderBehavior.StrictlyDecreasing
                "monotonic" -> TemporalOrderBehavior.Monotonic
                "non-monotonic" -> TemporalOrderBehavior.NonMonotonic
                else -> null.also { diagnostics += schemaError("Unknown traits.order: $entry", sourcePath, documentId) }
            },
            invertibility = when (val entry = string("invertibility")) {
                null -> null
                "invertible" -> TemporalInvertibility.Invertible
                "conditionally-invertible" -> TemporalInvertibility.ConditionallyInvertible
                "non-invertible" -> TemporalInvertibility.NonInvertible
                else -> null.also { diagnostics += schemaError("Unknown traits.invertibility: $entry", sourcePath, documentId) }
            },
            continuity = when (val entry = string("continuity")) {
                null -> null
                "continuous" -> TemporalContinuity.Continuous
                "piecewise" -> TemporalContinuity.Piecewise
                "discrete" -> TemporalContinuity.Discrete
                else -> null.also { diagnostics += schemaError("Unknown traits.continuity: $entry", sourcePath, documentId) }
            },
        )
    }

    private fun reportUnknownFields(
        map: YamlMap,
        allowed: Set<String>,
        field: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ) {
        (map.map.keys - allowed).forEach {
            diagnostics += schemaError("Unknown $field field: $it", sourcePath, documentId)
        }
    }

    private fun parseExactRational(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        field: String,
    ): ExactRational? {
        val raw = when (value) {
            is YamlInteger -> value.value.toString()
            is YamlNumber -> value.raw
            is YamlString -> value.value
            else -> null
        }
        if (raw == null) {
            diagnostics += schemaError(
                "$field MUST be an integer, decimal, scientific notation, or fraction",
                sourcePath,
                documentId,
            )
            return null
        }
        return runCatching { ExactRational.parse(raw) }.getOrElse {
            diagnostics += schemaError("Invalid $field: ${it.message}", sourcePath, documentId)
            null
        }
    }

    private fun parseTemporalCoordinate(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        field: String,
    ): TemporalCoordinate? = when (value) {
        is YamlInteger -> TemporalCoordinate.Rational(ExactRational.of(value.value))
        is YamlNumber -> runCatching { TemporalCoordinate.Rational(ExactRational.parse(value.raw)) }.getOrElse {
            diagnostics += schemaError("Invalid $field: ${it.message}", sourcePath, documentId)
            null
        }
        is YamlString -> parseGenericTemporalCoordinate(value.value)
        is YamlMap -> {
            val year = (value.map["year"] as? YamlInteger)?.value
            val month = (value.map["month"] as? YamlInteger)?.value?.toInt()
            val day = (value.map["day"] as? YamlInteger)?.value?.toInt()
            val era = (value.map["era"] as? YamlString)?.value
            when {
                year != null && month != null && day != null && era != null -> TemporalCoordinate.EraDate(era, year, month, day)
                year != null && month != null && day != null -> TemporalCoordinate.CalendarDate(year, month, day)
                else -> null.also { diagnostics += schemaError("$field has an unknown coordinate shape", sourcePath, documentId) }
            }
        }
        else -> null.also { diagnostics += schemaError("$field MUST be a temporal coordinate", sourcePath, documentId) }
    }

    private fun temporalCoordinateText(value: YamlValue): String? = when (value) {
        is YamlString -> value.value
        is YamlInteger -> value.value.toString()
        is YamlNumber -> value.raw
        else -> null
    }

    private fun validateTimelineProps(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ) {
        val props = value as? YamlMap ?: run {
            diagnostics += schemaError("props MUST be a mapping", sourcePath, documentId)
            return
        }
        props.map["note"]?.let {
            if (it !is YamlString) diagnostics += schemaError("props.note MUST be a string", sourcePath, documentId)
        }
        props.map["label"]?.let { labelValue ->
            val label = labelValue as? YamlMap ?: run {
                diagnostics += schemaError("props.label MUST be a mapping", sourcePath, documentId)
                return@let
            }
            if (label.map["default"] !is YamlString) {
                diagnostics += schemaError("props.label.default is required and MUST be a string", sourcePath, documentId)
            }
            label.map.forEach { (key, localized) ->
                if (localized !is YamlString) diagnostics += schemaError("props.label.$key MUST be a string", sourcePath, documentId)
            }
        }
    }

    private fun parsePropSchemaMap(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): Map<String, PropSchema> {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$fieldName MUST be a mapping", sourcePath, documentId)
            return emptyMap()
        }
        return map.map.mapValues { (name, schemaValue) ->
            parsePropSchema(schemaValue, sourcePath, diagnostics, documentId, "$fieldName.$name")
        }
    }

    private fun parsePropSchema(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): PropSchema {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$fieldName MUST be a mapping", sourcePath, documentId)
            return PropSchema(PropType.string)
        }
        val type = map.requireString("type", sourcePath, diagnostics, documentId)?.let {
            when (it) {
                "number" -> PropType.number
                "string" -> PropType.string
                "text" -> PropType.text
                "instant" -> PropType.instant
                "duration" -> PropType.duration
                "array" -> PropType.array
                else -> {
                    diagnostics += schemaError("Unknown prop type: $it", sourcePath, documentId)
                    null
                }
            }
        } ?: PropType.string
        val unknown = map.map.keys - setOf("type", "required", "timeline", "items", "enum")
        unknown.forEach { diagnostics += schemaError("Unknown property schema field: $fieldName.$it", sourcePath, documentId) }
        return PropSchema(
            type = type,
            required = map.boolean("required", sourcePath, diagnostics, documentId) ?: false,
            timeline = map.map["timeline"]?.takeUnless { it is YamlList }?.let {
                parseTimelineSelector(it, sourcePath, diagnostics, documentId, "$fieldName.timeline")
            },
            timelines = (map.map["timeline"] as? YamlList)?.let {
                parseTimelineSelectors(it, sourcePath, diagnostics, documentId, "$fieldName.timeline")
            },
            items = map.map["items"]?.let {
                when (it) {
                    is YamlString -> parsePropSchema(
                        YamlMap(linkedMapOf<String, YamlValue>("type" to it)), sourcePath, diagnostics, documentId, "$fieldName.items",
                    )
                    else -> parsePropSchema(it, sourcePath, diagnostics, documentId, "$fieldName.items")
                }
            },
            enumValues = map.map["enum"]?.let {
                parsePropEnum(it, sourcePath, diagnostics, documentId, "$fieldName.enum")
            },
        )
    }

    private fun parsePropEnum(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): List<RawValue>? {
        val values = (value as? YamlList)?.values ?: run {
            diagnostics += schemaError("$fieldName MUST be a non-empty list", sourcePath, documentId)
            return null
        }
        if (values.isEmpty()) {
            diagnostics += schemaError("$fieldName MUST be a non-empty list", sourcePath, documentId)
        }
        val rawValues = values.map(::parseRawValue)
        if (!rawValuesAreUnique(rawValues)) {
            diagnostics += schemaError("$fieldName values MUST be unique", sourcePath, documentId)
        }
        return rawValues
    }

    private fun parseTimelineMapping(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TimelineMapping? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("mapping MUST be a mapping", sourcePath, documentId)
            return null
        }
        return when (val kind = map.requireString("kind", sourcePath, diagnostics, documentId)) {
            "offset" -> {
                val from = map.string("from", sourcePath, diagnostics, documentId)
                val to = map.string("to", sourcePath, diagnostics, documentId)
                if ((from == null) == (to == null)) {
                    diagnostics += schemaError("offset mapping requires exactly one of from or to", sourcePath, documentId)
                    return null
                }
                val offset = when (val raw = map.map["offset"]) {
                    is YamlInteger -> raw.value.toDouble()
                    is YamlNumber -> raw.value
                    else -> {
                        diagnostics += schemaError("mapping.offset MUST be a number", sourcePath, documentId)
                        return null
                    }
                }
                if (!offset.isFinite()) {
                    diagnostics += schemaError("mapping.offset MUST be finite", sourcePath, documentId)
                    return null
                }
                val unknown = map.map.keys - setOf("kind", "from", "to", "offset")
                unknown.forEach { diagnostics += schemaError("Unknown mapping field: $it", sourcePath, documentId) }
                OffsetTimelineMapping(to = to, from = from, offset = offset)
            }
            null -> null
            else -> {
                diagnostics += schemaError("Unknown mapping kind: $kind", sourcePath, documentId)
                null
            }
        }
    }

    private fun parseTimecodeSchema(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TimecodeSchema? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("timecode MUST be a mapping", sourcePath, documentId)
            return null
        }
        val type = map.requireString("type", sourcePath, diagnostics, documentId) ?: return null
        if (type != "number") {
            diagnostics += schemaError("Unknown timecode type: $type", sourcePath, documentId)
            return null
        }
        (map.map.keys - setOf("type")).forEach {
            diagnostics += schemaError("Unknown timecode field: $it", sourcePath, documentId)
        }
        return TimecodeSchema(type = TimecodeType.number)
    }

    private fun parseRawObjectEntries(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): Map<String, RawValue> {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$fieldName MUST be a mapping", sourcePath, documentId)
            return emptyMap()
        }
        return map.map.mapValues { (_, rawValue) -> parseRawValue(rawValue) }
    }

    private fun parseRawValue(value: YamlValue): RawValue = when (value) {
        YamlNull -> RawNull
        is YamlBoolean -> RawBoolean(value.value)
        is YamlInteger -> RawInteger(value.value)
        is YamlNumber -> RawNumber(value.value)
        is YamlString -> RawString(value.value)
        is YamlList -> RawArray(value.values.map(::parseRawValue))
        is YamlMap -> RawObject(value.map.mapValues { parseRawValue(it.value) })
    }

    private fun syntaxError(message: String, sourcePath: String, documentId: String? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.SyntaxError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun schemaError(message: String, sourcePath: String, documentId: String? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun schemaWarning(message: String, sourcePath: String, documentId: String? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.SchemaError, Severity.Warning, message, SourceInfo(sourcePath, documentId))
}

private data class FrontMatterSplit(
    val frontMatter: String,
    val body: String,
)

private sealed interface YamlValue
private data object YamlNull : YamlValue
private data class YamlBoolean(val value: Boolean) : YamlValue
private data class YamlInteger(val value: Long) : YamlValue
private data class YamlNumber(val value: Double, val raw: String = value.toString()) : YamlValue
private data class YamlString(val value: String) : YamlValue
private data class YamlList(val values: List<YamlValue>) : YamlValue
private data class YamlMap(val map: LinkedHashMap<String, YamlValue>) : YamlValue

private class MiniYamlParser(
    text: String,
    private val sourcePath: String,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private val lines = text.split('\n').map { stripYamlComment(it).trimEnd() }
    private var index = 0

    fun parse(): YamlValue? {
        skipIgnorable()
        if (index >= lines.size) {
            diagnostics += syntaxError("YAML front matter is empty")
            return null
        }
        val value = parseBlock(indentOf(lines[index]))
        skipIgnorable()
        return value
    }

    private fun parseBlock(expectedIndent: Int): YamlValue? {
        skipIgnorable()
        if (index >= lines.size) return null
        val line = lines[index]
        val indent = indentOf(line)
        if (indent < expectedIndent) return null
        if (indent > expectedIndent) {
            diagnostics += syntaxError("Unexpected indentation in YAML front matter")
            return null
        }
        return if (line.drop(indent).startsWith("- ")) parseList(expectedIndent) else parseMap(expectedIndent)
    }

    private fun parseMap(expectedIndent: Int): YamlMap {
        val result = linkedMapOf<String, YamlValue>()
        while (index < lines.size) {
            skipIgnorable()
            if (index >= lines.size) break
            val line = lines[index]
            val indent = indentOf(line)
            if (indent < expectedIndent) break
            if (indent > expectedIndent) {
                diagnostics += syntaxError("Unexpected indentation in YAML front matter")
                break
            }
            val content = line.drop(indent)
            if (content.startsWith("- ")) break
            val split = splitKeyValue(content)
            if (split == null) {
                diagnostics += syntaxError("Invalid YAML mapping entry: $content")
                index++
                continue
            }
            index++
            val inlineValue = split.second
            val value = if (inlineValue == null) {
                parseNestedBlock(expectedIndent) ?: YamlNull
            } else {
                parseInlineValue(inlineValue)
            }
            result[split.first] = value
        }
        return YamlMap(LinkedHashMap(result))
    }

    private fun parseList(expectedIndent: Int): YamlList {
        val result = mutableListOf<YamlValue>()
        while (index < lines.size) {
            skipIgnorable()
            if (index >= lines.size) break
            val line = lines[index]
            val indent = indentOf(line)
            if (indent < expectedIndent) break
            if (indent != expectedIndent || !line.drop(indent).startsWith("- ")) break
            val remainder = line.drop(indent + 2)
            index++
            val value = when {
                remainder.isBlank() -> parseNestedBlock(expectedIndent) ?: YamlNull
                looksLikeInlineMapEntry(remainder) -> parseListItemMap(remainder, expectedIndent + 2)
                else -> parseInlineValue(remainder)
            }
            result += value
        }
        return YamlList(result)
    }

    private fun parseListItemMap(firstEntry: String, nestedIndent: Int): YamlMap {
        val split = splitKeyValue(firstEntry)
        val result = linkedMapOf<String, YamlValue>()
        if (split != null) {
            result[split.first] = split.second?.let(::parseInlineValue) ?: (parseNestedBlock(nestedIndent - 2) ?: YamlNull)
        } else {
            diagnostics += syntaxError("Invalid YAML mapping entry: $firstEntry")
        }
        while (index < lines.size) {
            skipIgnorable()
            if (index >= lines.size) break
            val line = lines[index]
            val indent = indentOf(line)
            if (indent < nestedIndent) break
            if (indent > nestedIndent) {
                diagnostics += syntaxError("Unexpected indentation in YAML front matter")
                break
            }
            val content = line.drop(indent)
            if (content.startsWith("- ")) break
            val next = splitKeyValue(content)
            if (next == null) {
                diagnostics += syntaxError("Invalid YAML mapping entry: $content")
                index++
                continue
            }
            index++
            result[next.first] = next.second?.let(::parseInlineValue) ?: (parseNestedBlock(nestedIndent) ?: YamlNull)
        }
        return YamlMap(LinkedHashMap(result))
    }

    private fun parseNestedBlock(parentIndent: Int): YamlValue? {
        skipIgnorable()
        if (index >= lines.size) return null
        val actualIndent = indentOf(lines[index])
        if (actualIndent <= parentIndent) return null
        return parseBlock(actualIndent)
    }

    private fun parseInlineValue(raw: String): YamlValue {
        val value = stripYamlTrailingComment(raw).trim()
        if (value.isEmpty()) return YamlNull
        if (value.startsWith("[") && value.endsWith("]")) {
            val inner = value.substring(1, value.lastIndex)
            if (inner.isBlank()) return YamlList(emptyList())
            return YamlList(
                splitYamlFlowItems(inner)
                    .map { it.raw.trim() }
                    .filter { it.isNotEmpty() }
                    .map(::parseInlineValue),
            )
        }
        if (value.startsWith("{") && value.endsWith("}")) {
            val inner = value.substring(1, value.lastIndex)
            if (inner.isBlank()) return YamlMap(linkedMapOf())
            val entries = linkedMapOf<String, YamlValue>()
            splitYamlFlowItems(inner).forEach { slice ->
                val entry = slice.raw.trim()
                val colon = findYamlMappingColon(entry)
                if (colon <= 0) {
                    diagnostics += syntaxError("Invalid YAML flow mapping entry: $entry")
                } else {
                    val key = decodeYamlScalar(entry.substring(0, colon))
                    entries[key] = parseInlineValue(entry.substring(colon + 1))
                }
            }
            return YamlMap(LinkedHashMap(entries))
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
            return YamlString(decodeYamlScalar(value))
        }
        if (value.startsWith("'") && value.endsWith("'") && value.length >= 2) {
            return YamlString(decodeYamlScalar(value))
        }
        return when {
            value == "null" -> YamlNull
            value == "true" -> YamlBoolean(true)
            value == "false" -> YamlBoolean(false)
            value.matches(Regex("[-+]?[0-9]+")) -> YamlInteger(value.toLong())
            value.matches(Regex("[-+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?")) ->
                YamlNumber(value.toDouble(), value)
            else -> YamlString(value)
        }
    }

    private fun splitKeyValue(content: String): Pair<String, String?>? {
        val colonIndex = findYamlMappingColon(content)
        if (colonIndex <= 0) return null
        val key = decodeYamlScalar(content.substring(0, colonIndex))
        if (key.isEmpty()) return null
        val rest = content.substring(colonIndex + 1)
        return key to rest.takeIf { it.isNotBlank() }?.trim()
    }

    private fun looksLikeInlineMapEntry(content: String): Boolean {
        val colonIndex = findYamlMappingColon(content)
        return colonIndex > 0
    }

    private fun skipIgnorable() {
        while (index < lines.size && lines[index].isBlank()) index++
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }

    private fun syntaxError(message: String): Diagnostic =
        Diagnostic(DiagnosticCategory.SyntaxError, Severity.Error, message, SourceInfo(sourcePath))
}

private fun YamlMap.requireString(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): String? {
    val value = map[key] ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key is required", SourceInfo(sourcePath, documentId))
        return null
    }
    return (value as? YamlString)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a string", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.string(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): String? {
    val value = map[key] ?: return null
    return (value as? YamlString)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a string", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.boolean(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): Boolean? {
    val value = map[key] ?: return null
    return (value as? YamlBoolean)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a boolean", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.long(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): Long? {
    val value = map[key] ?: return null
    return (value as? YamlInteger)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be an integer", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.stringList(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): List<String>? {
    val value = map[key] ?: return null
    return when (value) {
        is YamlList -> value.values.mapNotNull {
            (it as? YamlString)?.value ?: run {
                diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key items MUST be strings", SourceInfo(sourcePath, documentId))
                null
            }
        }.also { values ->
            if (values.isEmpty()) {
                diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a non-empty list", SourceInfo(sourcePath, documentId))
            }
            values.filter(String::isEmpty).forEach {
                diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key items MUST be non-empty", SourceInfo(sourcePath, documentId))
            }
            if (values.distinct().size != values.size) {
                diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key items MUST be unique", SourceInfo(sourcePath, documentId))
            }
        }
        else -> {
            diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a list of strings", SourceInfo(sourcePath, documentId))
            null
        }
    }
}

private fun parseTimelineSelector(
    value: YamlValue,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String?,
    fieldName: String,
): TimelineSelector? {
    return when (value) {
        is YamlString -> TimelineSelector.Id(value.value)
        is YamlMap -> {
            val explicitId = (value.map["id"] as? YamlString)?.value
            val compact = value.map.entries.singleOrNull()?.takeIf { it.key !in setOf("id", "mapped") }
            val id = explicitId ?: compact?.key
            diagnostics += Diagnostic(
                DiagnosticCategory.SchemaError,
                Severity.Error,
                "$fieldName mapped selectors were removed; use a Timeline ID and relate its Axis with sameAxisAs",
                SourceInfo(sourcePath, documentId),
            )
            id?.let(TimelineSelector::Id)
        }
        else -> {
            diagnostics += Diagnostic(
                DiagnosticCategory.SchemaError,
                Severity.Error,
                "$fieldName MUST be a Timeline identifier or a list of Timeline identifiers",
                SourceInfo(sourcePath, documentId),
            )
            null
        }
    }
}

private fun parseTimelineSelectors(
    value: YamlValue,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String?,
    fieldName: String,
): List<TimelineSelector>? {
    return when (value) {
        is YamlList -> value.values.mapNotNull {
            parseTimelineSelector(it, sourcePath, diagnostics, documentId, fieldName)
        }.also {
            if (value.values.isEmpty()) {
                diagnostics += Diagnostic(
                    DiagnosticCategory.SchemaError,
                    Severity.Error,
                    "$fieldName MUST be a non-empty list",
                    SourceInfo(sourcePath, documentId),
                )
            }
        }
        else -> parseTimelineSelector(value, sourcePath, diagnostics, documentId, fieldName)?.let(::listOf)
    }
}
