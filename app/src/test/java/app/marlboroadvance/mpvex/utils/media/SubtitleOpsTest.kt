package app.marlboroadvance.mpvex.utils.media

import android.app.Application
import `is`.xyz.mpv.ShadowMPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests sibling-subtitle autoloading at the player boundary: loading happens
 * through MPVLib "sub-add" commands, intercepted here by [ShadowMPVLib].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ShadowMPVLib::class])
class SubtitleOpsTest {
  private lateinit var videoPath: Path

  @Before
  fun setUp() {
    ShadowMPVLib.reset()
    Dispatchers.setMain(StandardTestDispatcher())

    val dir = Files.createTempDirectory("mpvex-sub-test")
    videoPath = dir.resolve("movie.mkv")
    Files.createFile(videoPath)
    Files.createFile(dir.resolve("movie.srt"))
    Files.createFile(dir.resolve("movie.ass"))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    videoPath.parent.toFile().deleteRecursively()
  }

  private fun subAddFlags(): List<String> =
    ShadowMPVLib.commands
      .filter { it.firstOrNull() == "sub-add" }
      .map { it[2] }

  @Test
  fun `autoload with show-by-default off loads sibling subtitles unselected`() = runTest {
    SubtitleOps.autoloadSubtitles(videoPath.toString(), "movie.mkv", selectFirstSubtitle = false)

    assertEquals(listOf("auto", "auto"), subAddFlags())
  }

  @Test
  fun `autoload with show-by-default on selects first sibling subtitle`() = runTest {
    SubtitleOps.autoloadSubtitles(videoPath.toString(), "movie.mkv")

    assertEquals(listOf("select", "auto"), subAddFlags())
  }
}
