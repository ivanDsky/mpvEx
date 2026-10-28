package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Bitmap.CompressFormat
import android.util.LruCache
import java.io.File

/**
 * Frame store for network files: ONE frame per file, keyed by connection +
 * path. The player captures the current frame during playback (so the stored
 * frame tracks where you stopped); readers never touch the network.
 *
 * Episodes never played get their first frame on first view (extraction
 * through the streaming proxy), which then serves as the thumbnail.
 */
class NetworkThumbnailCache private constructor(
  private val context: Context,
) {
  private val memoryCache =
    object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

  private fun memoryKey(connectionId: Long, path: String): String = "$connectionId|$path"

  private fun diskFile(connectionId: Long, path: String): File =
    File(File(context.cacheDir, "network_thumbs"), "${connectionId}_${path.hashCode()}.jpg")

  /** Stored frame, if any (memory or disk) — instant, never touches the network. */
  fun cached(connectionId: Long, path: String): Bitmap? =
    memoryCache.get(memoryKey(connectionId, path))
      ?: loadFromDisk(connectionId, path)

  /** Store/replace the frame for a file. */
  fun put(connectionId: Long, path: String, bitmap: Bitmap) {
    memoryCache.put(memoryKey(connectionId, path), bitmap)
    writeToDisk(connectionId, path, bitmap)
  }

  private fun loadFromDisk(connectionId: Long, path: String): Bitmap? =
    runCatching {
      diskFile(connectionId, path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

  private fun writeToDisk(connectionId: Long, path: String, bitmap: Bitmap) {
    runCatching {
      val target = diskFile(connectionId, path)
      target.parentFile?.mkdirs()
      target.outputStream().use { out ->
        bitmap.compress(CompressFormat.JPEG, JPEG_QUALITY, out)
      }
    }
    evictOldEntries()
  }

  /** Keep the disk cache bounded: oldest files first, [DISK_CACHE_BYTES] total. */
  private fun evictOldEntries() {
    runCatching {
      val dir = File(context.cacheDir, "network_thumbs")
      val files = dir.listFiles() ?: return@runCatching
      var total = files.sumOf { it.length() }
      if (total <= DISK_CACHE_BYTES) return@runCatching
      files
        .sortedBy { it.lastModified() }
        .forEach { file ->
          if (total <= DISK_CACHE_BYTES) return@forEach
          total -= file.length()
          file.delete()
        }
    }
  }

  companion object {
    private const val JPEG_QUALITY = 85
    private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

    @Volatile
    private var instance: NetworkThumbnailCache? = null

    fun get(context: Context): NetworkThumbnailCache =
      instance ?: synchronized(this) {
        instance ?: NetworkThumbnailCache(context.applicationContext).also { instance = it }
      }
  }
}
