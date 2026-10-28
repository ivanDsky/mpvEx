package app.marlboroadvance.mpvex.ui.player

import android.app.Application
import `is`.xyz.mpv.ShadowMPVLib
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.preferences.preference.FakePreferenceStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests subtitle track selection at the player boundary: every mpv read/write
 * goes through MPVLib, intercepted here by [ShadowMPVLib].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ShadowMPVLib::class])
class TrackSelectorTest {
  private lateinit var store: FakePreferenceStore
  private lateinit var subtitlesPreferences: SubtitlesPreferences
  private lateinit var trackSelector: TrackSelector

  private val intProps get() = ShadowMPVLib.intProps
  private val stringProps get() = ShadowMPVLib.stringProps

  @Before
  fun setUp() {
    ShadowMPVLib.reset()

    store = FakePreferenceStore()
    subtitlesPreferences = SubtitlesPreferences(store)
    trackSelector = TrackSelector(AudioPreferences(store), subtitlesPreferences)
  }

  /** A video track (id 1) plus two subtitle tracks: eng (id 2) and fre (id 3). */
  private fun givenVideoWithSubTracks() {
    stubTrack(index = 0, id = 1, type = "video")
    stubTrack(index = 1, id = 2, type = "sub", lang = "eng")
    stubTrack(index = 2, id = 3, type = "sub", lang = "fre")
    intProps["track-list/count"] = 3
  }

  private fun stubTrack(index: Int, id: Int, type: String, lang: String = "", title: String = "") {
    intProps["track-list/$index/id"] = id
    stringProps["track-list/$index/type"] = type
    stringProps["track-list/$index/lang"] = lang
    stringProps["track-list/$index/title"] = title
  }

  @Test
  fun `show-by-default off leaves subtitle tracks unselected on fresh playback`() = runTest {
    subtitlesPreferences.enabledByDefault.set(false)
    givenVideoWithSubTracks()

    trackSelector.onFileLoaded(hasState = false)

    // the fixture never sets "sid" — its presence would mean an auto-selection
    assertNull(intProps["sid"])
  }

  @Test
  fun `show-by-default on keeps automatic selection`() = runTest {
    givenVideoWithSubTracks()

    trackSelector.onFileLoaded(hasState = false)

    assertEquals(2, intProps["sid"])
  }

  @Test
  fun `saved playback state wins even when show-by-default is off`() = runTest {
    subtitlesPreferences.enabledByDefault.set(false)
    givenVideoWithSubTracks()
    // watch-later restored a manual fre selection before the selector runs
    intProps["sid"] = 3

    trackSelector.onFileLoaded(hasState = true)

    // untouched: auto-selection would have picked the eng track (id 2)
    assertEquals(3, intProps["sid"])
  }
}
