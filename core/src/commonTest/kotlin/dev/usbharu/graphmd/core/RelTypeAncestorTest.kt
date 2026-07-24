package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.RelTypeDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class RelTypeAncestorTest {
    @Test
    fun `resolved relation type exposes transitive ancestors`() {
        val result = GraphCompiler().compile(
            listOf(
                RelTypeDocument(id = "related", sourcePath = "related.md"),
                RelTypeDocument(id = "knows", extends = listOf("related"), sourcePath = "knows.md"),
                RelTypeDocument(id = "friend", extends = listOf("knows"), sourcePath = "friend.md"),
            ),
        )

        assertEquals(setOf("related", "knows"), result.relTypes.single { it.id == "friend" }.ancestorIds)
    }
}
