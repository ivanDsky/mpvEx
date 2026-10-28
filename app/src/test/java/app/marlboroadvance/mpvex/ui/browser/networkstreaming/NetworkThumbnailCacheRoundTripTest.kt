package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Triple-check for the thumbnail cache round trip:
 * put -> cached (memory hit), put -> cached (fresh instance = cold start),
 * the disk file actually existing, and observe() broadcasting replacements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkThumbnailCacheRoundTripTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()

  private fun frame(color: Int): Bitmap =
    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

  private fun newCache() = NetworkThumbnailCache(context)

  @Test
  fun `put then cached hits from memory`() {
    val cache = newCache()
    cache.put(7L, "Movies/ep5.mp4", frame(0xFF0000))

    assertEquals(0xFF0000, cache.cached(7L, "Movies/ep5.mp4")?.getPixel(4, 4))
  }

  @Test
  fun `put then cached loads from disk on a fresh instance`() {
    val cache = newCache()
    cache.put(7L, "Movies/ep5.mp4", frame(0xFF0000))

    // Robolectric's JPEG codec decodes to black, so assert presence, not pixels.
    assertNotNull(newCache().cached(7L, "Movies/ep5.mp4"))
  }

  @Test
  fun `disk file exists after put`() {
    val cache = newCache()
    cache.put(8L, "Show/ep1.mp4", frame(0x0000FF))

    val dir = java.io.File(context.cacheDir, "network_thumbs")
    val files = dir.listFiles().orEmpty().map { it.name }
    assertTrue(
      "expected a disk entry for conn 8, got: " + files,
      files.any { it.startsWith("8_") },
    )
  }

  @Test
  fun `unrelated file stays a miss`() {
    val cache = newCache()
    cache.put(7L, "Movies/ep5.mp4", frame(0xFF0000))

    assertNull(cache.cached(7L, "Movies/ep6.mp4"))
  }

  @Test
  fun `observe emits the stored frame then every replacement`() = runTest {
    val cache = newCache()
    cache.put(9L, "Show/ep1.mp4", frame(0x111111))

    val collected = mutableListOf<Bitmap?>()
    val job = launch {
      cache.observe(9L, "Show/ep1.mp4").take(2).toList(collected)
    }
    runCurrent() // collector subscribed, initial frame delivered
    cache.put(9L, "Show/ep1.mp4", frame(0x222222))
    runCurrent() // replacement delivered
    job.cancelAndJoin()

    assertEquals(2, collected.size)
    assertEquals(0x111111, collected[0]?.getPixel(4, 4))
    assertEquals(0x222222, collected[1]?.getPixel(4, 4))
  }

  @Test
  fun `observe filters other keys`() = runTest {
    val cache = newCache()

    val collected = mutableListOf<Bitmap?>()
    val job = launch {
      cache.observe(9L, "Show/ep1.mp4").take(2).toList(collected)
    }
    runCurrent()
    cache.put(9L, "Show/ep2.mp4", frame(0x333333))
    runCurrent()
    cache.put(10L, "Show/ep1.mp4", frame(0x444444))
    runCurrent()
    cache.put(9L, "Show/ep1.mp4", frame(0x555555))
    runCurrent()
    job.cancelAndJoin()

    assertEquals(2, collected.size)
    assertNull(collected[0])
    assertEquals(0x555555, collected[1]?.getPixel(4, 4))
  }
}
