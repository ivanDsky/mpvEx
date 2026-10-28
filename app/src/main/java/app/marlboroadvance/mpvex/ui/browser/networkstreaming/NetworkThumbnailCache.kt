package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Bitmap.CompressFormat
import android.net.Uri
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Frame store for network files.
 *
 * Two sources feed it:
 * - extraction through the streaming proxy (first frames, fast even on
 *   large files) for episodes never played;
 * - frame grabs taken by the player DURING playback, which land at the
 *   watched position and override the first-frame entry.
 *
 * Reads are memory/disk only — nothing here touches the network on startup.
 */
class NetworkThumbnailCache private constructor(
  private val context: Context,
  private val playbackStarter: NetworkPlaybackStarter,
) {
  private val memoryCache =
    object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
  private val extractMutex = Mutex()

  /** Cached frame, if any (memory or disk) — never touches the network. */
  fun cached(
    connectionId: Long,
    path: String,
  ): Bitmap? = memoryCache.get(memoryKey(connectionId, path)) ?: loadFromDisk(connectionId, path)

  /** Store a frame (e.g. grabbed by the player during playback). */
  fun put(
    connectionId: Long,
    path: String,
    bitmap: Bitmap,
  ) {
    memoryCache.put(memoryKey(connectionId, path), bitmap)
    writeToDisk(connectionId, path, bitmap)
  }

  /**
   * Cached frame, or extract one through the streaming proxy at
   * [positionSec] (serialized so a big folder can't hammer the server).
   */
  suspend fun getOrLoad(
    connection: NetworkConnection,
    file: NetworkFile,
    positionSec: Double = DEFAULT_POSITION_SECONDS,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      cached(connection.id, file.path)?.let { return@withContext it }

      extractMutex.withLock {
        cached(connection.id, file.path)?.let { return@withContext it }

        val stream = playbackStarter.registerOneOffStream(connection, file)
        val bitmap =
          runCatching {
            FastThumbnails.generateAsync(
              stream.url,
              positionSec,
              DIMENSION,
              useHwDec = false,
            )
          }.getOrNull()
        bitmap?.let { put(connection.id, file.path, it) }
        playbackStarter.unregisterStream(stream.streamId)
        bitmap
      }
    }

  private fun memoryKey(
    connectionId: Long,
    path: String,
  ): String = "$connectionId|$path"

  private fun diskFile(
    connectionId: Long,
    path: String,
  ): File = File(File(context.cacheDir, "network_thumbs"), "${connectionId}_${path.hashCode()}.jpg")

  private fun loadFromDisk(
    connectionId: Long,
    path: String,
  ): Bitmap? =
    runCatching {
      diskFile(connectionId, path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

  private fun writeToDisk(
    connectionId: Long,
    path: String,
    bitmap: Bitmap,
  ) {
    runCatching {
      diskFile(connectionId, path).outputStream().use { out ->
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
    private const val DIMENSION = 256
    private const val JPEG_QUALITY = 85
    const val DEFAULT_POSITION_SECONDS = 2.0
    private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

    @Volatile
    private var instance: NetworkThumbnailCache? = null

    fun get(
      context: Context,
      playbackStarter: NetworkPlaybackStarter,
    ): NetworkThumbnailCache =
      instance ?: synchronized(this) {
        instance ?: NetworkThumbnailCache(context.applicationContext, playbackStarter).also { instance = it }
      }
  }
}
