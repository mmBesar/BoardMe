package com.mmbesar.boardme.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun stringNumbersAreAccepted() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":"7","project_id":"2","column_id":"3","swimlane_id":"1","title":"Buy milk",
               "description":"2 litres","position":"4","date_due":"1790000000","is_active":"1"}""",
        )
        assertEquals(7L, task.id)
        assertEquals(2L, task.projectId)
        assertEquals(3L, task.columnId)
        assertEquals(4L, task.position)
        assertEquals(1790000000L, task.dateDue)
        assertEquals(1L, task.isActive)
    }

    @Test
    fun realNumbersAreAcceptedToo() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":7,"project_id":2,"column_id":3,"title":"X","position":4,"is_active":0}""",
        )
        assertEquals(7L, task.id)
        assertEquals(0L, task.isActive)
    }

    @Test
    fun nullAndMissingFieldsBecomeDefaults() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":"1","title":"Only a title","description":null,"date_due":null,"extra":"ignored"}""",
        )
        assertEquals("", task.description)
        assertEquals(0L, task.dateDue)
        assertEquals(0L, task.columnId)
    }

    @Test
    fun projectAndColumnDecode() {
        val project = json.decodeFromString(
            ProjectDto.serializer(),
            """{"id":"1","name":"Home","is_active":"1","token":"abc"}""",
        )
        assertEquals("Home", project.name)
        assertEquals(1L, project.isActive)

        val column = json.decodeFromString(
            ColumnDto.serializer(),
            """{"id":"5","title":"Ready","position":"2","project_id":"1"}""",
        )
        assertEquals("Ready", column.title)
        assertEquals(2L, column.position)
    }
}
