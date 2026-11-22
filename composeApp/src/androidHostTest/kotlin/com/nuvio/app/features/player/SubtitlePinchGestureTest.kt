package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two-finger subtitle resize and move on the player surface (Build 15, smoothing):
 *  - two fingers that have not moved yet show nothing (no size or position hint flashes);
 *  - a pinch that crosses the dead zone starts from the current size (zoom 1.0), not with a jump;
 *  - a two-finger drag starts from the current position, not with a jump of the slop;
 *  - a finger lifted and put back during a pinch changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class SubtitlePinchGestureTest {
    @get:Rule val compose = createComposeRule()

    private val begins = mutableListOf<Unit>()
    private val updates = mutableListOf<Pair<Float, Float>>()
    private val ends = mutableListOf<Unit>()

    private fun setContent() {
        val callbacks = PlayerSubtitleGestureCallbacks(
            enabled = mutableStateOf(true),
            begin = mutableStateOf({ begins += Unit }),
            update = mutableStateOf({ zoom: Float, up: Float -> updates += zoom to up }),
            end = mutableStateOf({ ends += Unit }),
        )
        compose.setContent {
            Box(
                Modifier.size(480.dp, 270.dp)
                    .testTag("surface")
                    .playerSurfaceDragGestures(
                        gestureController = null,
                        layoutSize = IntSize(480, 270),
                        playbackGesturesEnabled = true,
                        sideGestureSystemEdgeExclusionPx = 0f,
                        playerControlsLockedState = mutableStateOf(false),
                        touchGesturesEnabledState = mutableStateOf(true),
                        isHoldToSpeedGestureActiveState = mutableStateOf(false),
                        currentPositionMsState = mutableStateOf(0L),
                        currentDurationMsState = mutableStateOf(600_000L),
                        deactivateHoldToSpeedState = mutableStateOf({}),
                        showHorizontalSeekPreviewState = mutableStateOf({ _: Long, _: Long -> }),
                        showBrightnessFeedbackState = mutableStateOf({ _: Float -> }),
                        showVolumeFeedbackState = mutableStateOf({ _: PlayerAudioLevel -> }),
                        clearLiveGestureFeedbackState = mutableStateOf({}),
                        revealLockedOverlayState = mutableStateOf({}),
                        commitHorizontalSeekState = mutableStateOf({ _: Long -> }),
                        subtitleGestures = callbacks,
                    ),
            )
        }
    }

    @Test
    fun twoFingersThatHaveNotMovedShowNothing() {
        setContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            // Within the dead zones: 2 px of spread on 120, 1 px up.
            moveBy(1, Offset(2f, -1f))
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(1, begins.size)
            assertEquals(emptyList(), updates, "a hint was shown before the fingers moved")
            assertEquals(1, ends.size)
        }
    }

    @Test
    fun aPinchStartsFromTheCurrentSize() {
        setContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            repeat(30) { moveBy(1, Offset(3f, 0f)) }
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertTrue(updates.isNotEmpty(), "the pinch did nothing")
            assertEquals(1f, updates.first().first, "the size jumped when the pinch began: ${updates.first()}")
            val zooms = updates.map { it.first }
            assertTrue(zooms.zipWithNext().all { (a, b) -> b >= a }, "the size went back while spreading: $zooms")
            assertTrue(zooms.last() > 1.4f, "spreading 120 to 210 px did not enlarge: ${zooms.last()}")
            assertTrue(updates.all { it.second == 0f }, "a sideways pinch moved the subtitles: $updates")
        }
    }

    @Test
    fun aTwoFingerDragStartsFromTheCurrentPosition() {
        setContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            repeat(20) {
                moveBy(0, Offset(0f, -4f))
                moveBy(1, Offset(0f, -4f))
            }
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertTrue(updates.isNotEmpty(), "the drag did nothing")
            // The first step after the slop is one finger's move (or less), never the whole slop.
            val height = compose.onNodeWithTag("surface").fetchSemanticsNode().size.height.toFloat()
            assertTrue(updates.first().second <= 4f / height + 1e-4f, "the position jumped when the drag began: ${updates.first()}")
            assertTrue(updates.last().second > 0f, "dragging up did not raise the subtitles")
            assertTrue(updates.all { it.first == 1f }, "a straight drag resized the subtitles: $updates")
        }
    }

    @Test
    fun aFingerPutBackDuringAPinchChangesNothing() {
        setContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            repeat(10) { moveBy(1, Offset(3f, 0f)) }
            up(1)
        }
        val before = compose.runOnIdle { updates.toList() }
        compose.onNodeWithTag("surface").performTouchInput {
            // Back down much closer: against the old start this read as a big shrink.
            down(1, center - Offset(40f, 0f))
            repeat(5) { moveBy(1, Offset(2f, 0f)) }
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertTrue(before.isNotEmpty(), "the pinch did nothing")
            assertEquals(before, updates, "a finger put back changed the subtitles")
            assertEquals(1, ends.size)
        }
    }
}
