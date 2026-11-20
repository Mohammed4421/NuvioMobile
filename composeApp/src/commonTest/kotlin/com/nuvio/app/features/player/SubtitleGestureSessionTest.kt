package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleGestureSessionTest {
    private val stored = mutableListOf<SubtitleStyleState>()
    private val session = SubtitleGestureSession(persist = { stored += it })
    private val start = SubtitleStyleState.DEFAULT

    private fun drag(frames: Int) {
        for (frame in 1..frames) {
            session.update(start) { it.copy(bottomOffset = it.bottomOffset + frame) }
        }
    }

    @Test
    fun aDragOfSixtyFramesIsStoredOnceWithItsFinalPosition() {
        session.begin(start)
        drag(60)
        assertTrue(stored.isEmpty(), "nothing is stored while the fingers move")
        assertEquals(start.bottomOffset + 60, session.preview?.bottomOffset)
        session.flush()
        assertEquals(listOf(start.copy(bottomOffset = start.bottomOffset + 60)), stored)
        assertNull(session.preview)
        assertFalse(session.active)
    }

    @Test
    fun aPinchKeepsTheSizeOfTheLastFrame() {
        session.begin(start)
        for (zoom in listOf(1.1f, 1.3f, 1.25f)) {
            session.update(start) { it.copy(fontSizeSp = (it.fontSizeSp * zoom).toInt()) }
        }
        session.flush()
        assertEquals((start.fontSizeSp * 1.25f).toInt(), stored.single().fontSizeSp)
    }

    @Test
    fun aGestureThatEndsWhereItStartedStoresNothing() {
        session.begin(start)
        session.update(start) { it.copy(bottomOffset = it.bottomOffset + 5) }
        session.update(start) { it }
        session.flush()
        assertTrue(stored.isEmpty())
    }

    @Test
    fun anUnfinishedGestureIsStoredWhenTheNextOneStarts() {
        session.begin(start)
        drag(3)
        // No end (for example the touch stream was cancelled); the next gesture starts.
        val shown = session.preview!!
        session.begin(shown)
        assertEquals(listOf(shown), stored)
        assertTrue(session.active)
    }

    @Test
    fun eachUpdateStartsFromTheStyleAtTheBeginningNotFromThePreview() {
        session.begin(start)
        session.update(start) { it.copy(bottomOffset = it.bottomOffset + 10) }
        val second = session.update(session.preview!!) { it.copy(bottomOffset = it.bottomOffset + 20) }
        assertEquals(start.bottomOffset + 20, second.bottomOffset)
    }
}
