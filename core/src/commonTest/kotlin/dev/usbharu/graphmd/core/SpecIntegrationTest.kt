package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.NumberValue
import dev.usbharu.graphmd.core.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecIntegrationTest {
    @Test
    fun `compiles the canonical spec sample end to end`() {
        val result = GraphCompiler().compileSources(
            listOf(
                source("CommonEra.md", """
                    ---
                    id: CommonEra
                    kind: Timeline
                    timecode:
                      type: number
                    ---
                """),
                source("ProjectEra.md", """
                    ---
                    id: ProjectEra
                    kind: Timeline
                    timecode:
                      type: number
                    mappings:
                      - from: CommonEra
                        kind: offset
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
                        timeline:
                          - CommonEra:
                              mapped: true
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
        assertEquals(1000.0, result.timelines.single { it.id == "CommonEra" }.mappedOffsets["ProjectEra"])
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
