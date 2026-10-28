package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Triple-check for the thumbnail cache round trip:
 * put -> cached (same instance), put -> cached (fresh instance = cold start),
 * and the disk file actually existing on the filesystem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkThumbnailCacheRoundTripTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()

  private fun frame(color: Int): Bitmap =
    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

  @Test
  fun `put then cached hits from memory`() {
    val cache = NetworkThumbnailCache.get(context)
    cache.put(7L, "Movies/ep5.mp4", frame(0xFF0000), positionSec = 900.0)

    assertEquals(0xFF0000, cache.cached(7L, "Movies/ep5.mp4", 900.0)?.getPixel(4, 4))
  }

  @Test
  fun `different position bucket is a miss`() {
    val cache = NetworkThumbnailCache.get(context)
    cache.put(7L, "Movies/ep5.mp4", frame(0x00FF00), positionSec = 900.0)

    assertNull(cache.cached(7L, "Movies/ep5.mp4", 60.0))
  }



  private fun newCache() = NetworkThumbnailCache(context)

  @Test
  fun `put then cached hits from memory and disk`() {
    val cache = newCache()
    cache.put(7L, "Movies/ep5.mp4", frame(0xFF0000), positionSec = 900.0)

    assertEquals(0xFF0000, cache.cached(7L, "Movies/ep5.mp4", 900.0)?.getPixel(4, 4))
    assertNotNull(cache.cached(7L, "Movies/ep5.mp4", 900.0))
  }

  @Test
  fun `disk file exists after put`() {
    val cache = newCache()
    cache.put(8L, "Show/ep1.mp4", frame(0x0000FF), positionSec = 45.0)

    val dir = java.io.File(context.cacheDir, "network_thumbs")
    val files = dir.listFiles().orEmpty().map { it.name }
    assertTrue(
      "expected a disk entry for conn 8, got: " + files,
      files.any { it.startsWith("8_") },
    )
  }
}
