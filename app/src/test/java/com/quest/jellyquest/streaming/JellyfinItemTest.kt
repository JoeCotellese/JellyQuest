package com.quest.jellyquest.streaming

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class JellyfinItemTest {

    @Test
    fun `serialization roundtrip preserves position fields`() {
        val item = JellyfinItem(
            id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            name = "Test Movie",
            type = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
            isFolder = false,
            playbackPositionTicks = 5_000_000_000L, // 500 seconds
            runTimeTicks = 72_000_000_000L, // 7200 seconds = 2 hours
        )

        val json = item.toJson()
        val restored = JellyfinItem.fromJson(json)

        assertEquals(item.id, restored.id)
        assertEquals(item.name, restored.name)
        assertEquals(item.type, restored.type)
        assertEquals(item.isFolder, restored.isFolder)
        assertEquals(item.playbackPositionTicks, restored.playbackPositionTicks)
        assertEquals(item.runTimeTicks, restored.runTimeTicks)
    }

    @Test
    fun `fromJson defaults position fields to 0 when missing`() {
        // Simulate cached JSON from before position fields were added
        val json = JSONObject().apply {
            put("id", "550e8400-e29b-41d4-a716-446655440000")
            put("name", "Old Cached Movie")
            put("type", "MOVIE")
            put("isFolder", false)
        }

        val item = JellyfinItem.fromJson(json)

        assertEquals(0L, item.playbackPositionTicks)
        assertEquals(0L, item.runTimeTicks)
    }

    @Test
    fun `serialization roundtrip with zero position fields`() {
        val item = JellyfinItem(
            id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            name = "Unwatched Movie",
            type = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
            isFolder = false,
        )

        val json = item.toJson()
        val restored = JellyfinItem.fromJson(json)

        assertEquals(0L, restored.playbackPositionTicks)
        assertEquals(0L, restored.runTimeTicks)
    }
}
