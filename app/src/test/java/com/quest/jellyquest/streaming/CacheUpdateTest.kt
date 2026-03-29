package com.quest.jellyquest.streaming

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class CacheUpdateTest {

    private val movieId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val libraryId = UUID.fromString("660e8400-e29b-41d4-a716-446655440000")

    private fun makeItem(
        id: UUID = movieId,
        name: String = "The Avengers",
        positionTicks: Long = 0,
        runTimeTicks: Long = 72_000_000_000L,
    ) = JellyfinItem(
        id = id,
        name = name,
        type = BaseItemKind.MOVIE,
        isFolder = false,
        playbackPositionTicks = positionTicks,
        runTimeTicks = runTimeTicks,
    )

    @Test
    fun `updateCachedItemPosition updates item in cached items map`() {
        val original = makeItem(positionTicks = 0)
        val cachedItems = mapOf(libraryId to listOf(original))

        val (updatedItems, _) = updateCachedItemPosition(
            cachedItems = cachedItems,
            cachedLibraries = null,
            itemId = movieId,
            positionTicks = 36_000_000_000L,
        )

        val updatedItem = updatedItems[libraryId]!!.first()
        assertEquals(36_000_000_000L, updatedItem.playbackPositionTicks)
        // Other fields unchanged
        assertEquals("The Avengers", updatedItem.name)
        assertEquals(72_000_000_000L, updatedItem.runTimeTicks)
    }

    @Test
    fun `updateCachedItemPosition returns unchanged maps when item not found`() {
        val unrelatedId = UUID.fromString("770e8400-e29b-41d4-a716-446655440000")
        val original = makeItem()
        val cachedItems = mapOf(libraryId to listOf(original))

        val (updatedItems, _) = updateCachedItemPosition(
            cachedItems = cachedItems,
            cachedLibraries = null,
            itemId = unrelatedId,
            positionTicks = 10_000_000_000L,
        )

        assertEquals(0L, updatedItems[libraryId]!!.first().playbackPositionTicks)
    }

    @Test
    fun `updateCachedItemPosition updates item across multiple libraries`() {
        val otherLibraryId = UUID.fromString("880e8400-e29b-41d4-a716-446655440000")
        val item1 = makeItem(positionTicks = 0)
        val item2 = makeItem(
            id = UUID.fromString("990e8400-e29b-41d4-a716-446655440000"),
            name = "Other Movie",
        )
        val cachedItems = mapOf(
            libraryId to listOf(item1, item2),
            otherLibraryId to listOf(item1.copy()),
        )

        val (updatedItems, _) = updateCachedItemPosition(
            cachedItems = cachedItems,
            cachedLibraries = null,
            itemId = movieId,
            positionTicks = 5_000_000_000L,
        )

        // Updated in first library
        assertEquals(5_000_000_000L, updatedItems[libraryId]!!.first { it.id == movieId }.playbackPositionTicks)
        // Updated in second library too
        assertEquals(5_000_000_000L, updatedItems[otherLibraryId]!!.first { it.id == movieId }.playbackPositionTicks)
        // Unrelated item untouched
        assertEquals(0L, updatedItems[libraryId]!!.first { it.name == "Other Movie" }.playbackPositionTicks)
    }

    @Test
    fun `updateCachedItemPosition updates cachedLibraries if item exists there`() {
        val libraries = listOf(makeItem(positionTicks = 0))

        val (_, updatedLibraries) = updateCachedItemPosition(
            cachedItems = emptyMap(),
            cachedLibraries = libraries,
            itemId = movieId,
            positionTicks = 20_000_000_000L,
        )

        assertEquals(20_000_000_000L, updatedLibraries!!.first().playbackPositionTicks)
    }

    @Test
    fun `updateCachedItemPosition preserves null cachedLibraries`() {
        val (_, updatedLibraries) = updateCachedItemPosition(
            cachedItems = emptyMap(),
            cachedLibraries = null,
            itemId = movieId,
            positionTicks = 10_000_000_000L,
        )

        assertEquals(null, updatedLibraries)
    }
}
