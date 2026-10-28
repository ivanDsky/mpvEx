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
 * Frame thumbnails for network files: memory LRU, disk cache keyed by
 * connection+path (stable across restarts), extraction through the streaming
 * proxy serialized so a big folder can't hammer the server.
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
    file: NetworkFile,
  ): Bitmap? = memoryCache.get(memoryKey(connectionId, file)) ?: loadFromDisk(connectionId, file)

  /** Cached frame, or extract one through the streaming proxy. */
  suspend fun getOrLoad(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      cached(connection.id, file)?.let { return@withContext it }

      extractMutex.withLock {
        cached(connection.id, file)?.let { return@withContext it }

        val stream = playbackStarter.registerOneOffStream(connection, file)
        val bitmap =
          runCatching {
            FastThumbnails.generateAsync(
              stream.url,
              PREFERRED_POSITION_SECONDS,
              DIMENSION,
              useHwDec = false,
            )
          }.getOrNull()
        bitmap?.let {
          memoryCache.put(memoryKey(connection.id, file), it)
          writeToDisk(connection.id, file, it)
        }
        playbackStarter.unregisterStream(stream.streamId)
        bitmap
      }
    }

  private fun memoryKey(
    connectionId: Long,
    file: NetworkFile,
  ): String = "$connectionId|${file.path}"

  private fun diskFile(
    connectionId: Long,
    file: NetworkFile,
  ): File =
    File(File(context.cacheDir, "network_thumbs"), "${connectionId}_${file.path.hashCode()}.jpg")

  private fun loadFromDisk(
    connectionId: Long,
    file: NetworkFile,
  ): Bitmap? =
    runCatching {
      diskFile(connectionId, file).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

  private fun writeToDisk(
    connectionId: Long,
    file: NetworkFile,
    bitmap: Bitmap,
  ) {
    runCatching {
      diskFile(connectionId, file).outputStream().use { out ->
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
    private const val PREFERRED_POSITION_SECONDS = 2.0
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
