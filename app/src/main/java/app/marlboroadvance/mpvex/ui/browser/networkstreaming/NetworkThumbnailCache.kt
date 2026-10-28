package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Bitmap.CompressFormat
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
 * Frames come from two sources: extraction through the streaming proxy at a
 * requested position (first frame for unwatched episodes, the saved resume
 * position for in-progress ones), and grabs the player takes DURING
 * playback at the watched position (which override extractions).
 *
 * Positions are bucketed (30 s) so a frame captured near where you stopped
 * counts as a hit; watching further on re-extracts at the new bucket.
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

  /** Cached frame for [positionSec], if any (memory or disk). */
  fun cached(
    connectionId: Long,
    path: String,
    positionSec: Double = DEFAULT_POSITION_SECONDS,
  ): Bitmap? =
    memoryCache.get(memoryKey(connectionId, path, positionSec))
      ?: loadFromDisk(connectionId, path, positionSec)

  /** Store a frame captured/extracted at [positionSec]. */
  fun put(
    connectionId: Long,
    path: String,
    bitmap: Bitmap,
    positionSec: Double = DEFAULT_POSITION_SECONDS,
  ) {
    memoryCache.put(memoryKey(connectionId, path, positionSec), bitmap)
    writeToDisk(connectionId, path, positionSec, bitmap)
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
      cached(connection.id, file.path, positionSec)?.let { return@withContext it }

      extractMutex.withLock {
        cached(connection.id, file.path, positionSec)?.let { return@withContext it }

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
        bitmap?.let { put(connection.id, file.path, it, positionSec) }
        playbackStarter.unregisterStream(stream.streamId)
        bitmap
      }
    }

  private fun positionBucket(positionSec: Double): Int =
    (positionSec / POSITION_BUCKET_SECONDS).toInt()

  private fun memoryKey(
    connectionId: Long,
    path: String,
    positionSec: Double,
  ): String = "$connectionId|$path|${positionBucket(positionSec)}"

  private fun diskFile(
    connectionId: Long,
    path: String,
    positionSec: Double,
  ): File = File(File(context.cacheDir, "network_thumbs"), "${connectionId}_${path.hashCode()}_p${positionBucket(positionSec)}.jpg")

  private fun loadFromDisk(
    connectionId: Long,
    path: String,
    positionSec: Double,
  ): Bitmap? =
    runCatching {
      diskFile(connectionId, path, positionSec).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

  private fun writeToDisk(
    connectionId: Long,
    path: String,
    positionSec: Double,
    bitmap: Bitmap,
  ) {
    runCatching {
      diskFile(connectionId, path, positionSec).outputStream().use { out ->
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
    private const val DIMENSION = 512
    private const val JPEG_QUALITY = 85
    const val DEFAULT_POSITION_SECONDS = 2.0
    private const val POSITION_BUCKET_SECONDS = 30
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
