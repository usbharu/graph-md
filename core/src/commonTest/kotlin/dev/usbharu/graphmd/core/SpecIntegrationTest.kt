package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecIntegrationTest {
    @Test
    fun `compiles and executes every temporal sample from the specification`() {
        val result = GraphCompiler().compileSources(
            listOf(
                source("Story.md", """
                    ---
                    id: Story
                    kind: Timeline
                    ---
                """),
                source("CommonEra.md", """
                    ---
                    id: CommonEra
                    kind: Timeline
                    coordinate: gregorian
                    ---
                """),
                source("JapaneseEra.md", """
                    ---
                    id: JapaneseEra
                    kind: Timeline
                    sameAxisAs: CommonEra
                    coordinate:
                      kind: era
                      periods:
                        - name: Reiwa
                          aliases: [令和, R]
                          since: 2019-05-01
                          firstYear: 1
                    ---
                """),
                source("Reality.md", """
                    ---
                    id: Reality
                    kind: Timeline
                    ---
                """),
                source("IfWorld.md", """
                    ---
                    id: IfWorld
                    kind: Timeline
                    derivedFrom:
                      timeline: Reality
                      kind: fork
                      sourceAt: 0
                      origin: 0
                    ---
                """),
                source("Recording.md", """
                    ---
                    id: Recording
                    kind: Timeline
                    coordinate: frame
                    derivedFrom:
                      timeline: Reality
                      kind: recording
                    mapsTo:
                      - timeline: Reality
                        kind: alignment
                        precision: exact
                        scale: 1/30
                        offset: 100
                        range: { from: 0, to: 3000 }
                    ---
                """),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val engine = TemporalEngine(result.temporalModel)
        assertEquals(
            TemporalComparisonResult.Ordered(TemporalOrder.Equal),
            engine.compare(engine.parse("CommonEra", "2019-05-01"), engine.parse("JapaneseEra", "令和 1-05-01")),
        )
        assertEquals(
            TemporalComparisonResult.Unrelated,
            engine.compare(engine.parse("Reality", "0"), engine.parse("IfWorld", "0")),
        )
        assertEquals(
            TemporalConversionResult.Exact(
                TemporalValue("Reality", TemporalCoordinate.Rational(ExactRational.of(101))),
            ),
            engine.convert(engine.parse("Recording", "30"), "Reality"),
        )
    }

    @Test
    fun `compiles the canonical spec sample end to end`() {
        val result = GraphCompiler().compileSources(
            listOf(
                source("CommonEra.md", """
                    ---
                    id: CommonEra
                    kind: Timeline
                    ---
                """),
                source("ProjectEra.md", """
                    ---
                    id: ProjectEra
                    kind: Timeline
                    sameAxisAs: CommonEra
                    offset: 1000
                    ---
                """),
                source("Person.md", """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      name:
                        type: text
                        required: true
                      age:
                        type: number
                      birthDate:
                        type: instant
                        timeline: CommonEra
                      arraySample:
                        type: array
                        items: number
                    ---
                """),
                source("friendOf.md", """
                    ---
                    id: friendOf
                    kind: RelType
                    from:
                      - Person
                    to:
                      - Person
                    props:
                      weight:
                        type: number
                    ---
                """),
                source("bob.md", """
                    ---
                    id: bob
                    kind: Node
                    type: Person
                    props:
                      name: Bob
                    ---
                """),
                source("alice.md", """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: CommonEra
                    props:
                      arraySample:
                        - 0
                        - value: 1
                          validTime:
                            - timeline: ProjectEra
                    ---

                    名前は@props{name=Alice}。
                    年齢は@props{age(validTime=CommonEra)=20,age(validTime=ProjectEra(from=1,to=2))=21}。
                    @link(validTime=CommonEra){weight=0.9}[Bob](bob "friendOf")
                """),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals(setOf("alice", "bob"), result.nodes.map { it.id }.toSet())
        assertEquals(
            result.timelines.single { it.id == "CommonEra" }.axisId,
            result.timelines.single { it.id == "ProjectEra" }.axisId,
        )
        val alice = result.nodes.single { it.id == "alice" }
        assertEquals(listOf(20.0, 21.0), alice.propEntries.getValue("age").map { (it.value as NumberValue).value })
        val relation = result.relations.single()
        assertEquals("alice", relation.from)
        assertEquals("bob", relation.to)
        assertEquals("friendOf", relation.type)
        assertEquals(0.9, (relation.props.getValue("weight") as NumberValue).value)
        assertEquals("CommonEra", relation.validTime.single().timeline)
    }

    private fun source(name: String, text: String): dev.usbharu.graphmd.core.model.SourceDocument =
        dev.usbharu.graphmd.core.model.SourceDocument(text.trimIndent(), "/tmp/$name")
}
