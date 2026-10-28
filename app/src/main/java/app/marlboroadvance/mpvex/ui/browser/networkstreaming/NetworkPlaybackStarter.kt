package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkFolderQueue
import app.marlboroadvance.mpvex.domain.network.NetworkRecent
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import android.graphics.Bitmap
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Launches network playback: registers the file (and its folder queue) with
 * the streaming proxy and hands the player the playlist. Shared by the
 * network browser and the Home shelf's "continue playlist" tap.
 */
class NetworkPlaybackStarter(
  private val application: Application,
  private val repository: NetworkRepository,
) {
  suspend fun play(
    connectionId: Long,
    file: NetworkFile,
    listing: List<NetworkFile>? = null,
  ): Result<Unit> =
    withContext(Dispatchers.Main) {
      runCatching {
        val connection = repository.getConnectionById(connectionId) ?: error("Connection not found")

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS

        // Queue the rest of the folder like local playback does: list the
        // parent, order videos naturally, register each sibling once.
        var queue: List<Uri> = emptyList()
        var queueIndex = 0
        if (useProxy) {
          val parentPath = file.path.substringBeforeLast("/", missingDelimiterValue = "")
          val folderListing = listing ?: repository.listFiles(connection, parentPath).getOrNull()
          val plan = folderListing?.let { NetworkFolderQueue.plan(it, file.path) }
          if (plan != null) {
            val proxy = NetworkStreamingProxy.getInstance()
            val timestamp = System.currentTimeMillis()
            queue =
              plan.siblings.mapIndexed { i, sibling ->
                proxyUri(connection, sibling, "${connectionId}_${timestamp}_$i", withFileName = true)
              }
            queueIndex = plan.currentIndex
          }
        }

        val uri =
          when {
            queue.isNotEmpty() -> queue[queueIndex]
            useProxy -> proxyUri(connection, file, "${connectionId}_${System.currentTimeMillis()}")
            else -> {
              NetworkStreamingProvider.setConnection(connectionId, connection)
              NetworkStreamingProvider.getUri(application, connectionId, file.path)
            }
          }

        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("launch_source", "network_stream")
        intent.putExtra("title", file.name)
        intent.putExtra("filename", file.name)
        // Pass the original network file path for stable media identifier (position saving)
        intent.putExtra(NetworkRecent.EXTRA_NETWORK_FILE_PATH, file.path)
        intent.putExtra(NetworkRecent.EXTRA_NETWORK_CONNECTION_ID, connectionId)
        if (queue.isNotEmpty()) {
          intent.putParcelableArrayListExtra("playlist", ArrayList(queue))
          intent.putExtra("playlist_index", queueIndex)
        }
        intent.setDataAndType(uri, file.mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        application.startActivity(intent)
      }
    }

  /**
   * Register a file with the streaming proxy for a one-off read (e.g.
   * thumbnail extraction) and hand the local URL to [block]. The stream
   * stays registered — extraction results are cached by the caller.
   */
  suspend fun withRegisteredStream(
    connection: NetworkConnection,
    file: NetworkFile,
    block: suspend (url: String) -> Bitmap?,
  ): Bitmap? {
    val proxy = NetworkStreamingProxy.getInstance()
    val streamId = "thumb_${connection.id}_${file.path.hashCode()}"
    val url =
      proxy.registerStream(
        streamId = streamId,
        connection = connection,
        filePath = file.path,
        fileSize = if (file.size > 0) file.size else -1L,
        mimeType = file.mimeType ?: "video/mp4",
      )
    return block(url)
  }

  /**
   * Register a file with the streaming proxy and return its playback URI.
   * When [withFileName] is set, the file name is appended to the path so
   * player titles derived from the URL show it; the proxy routes on the
   * first path segment only.
   */
  private fun proxyUri(
    connection: NetworkConnection,
    file: NetworkFile,
    streamId: String,
    withFileName: Boolean = false,
  ): Uri {
    val proxy = NetworkStreamingProxy.getInstance()
    val base =
      proxy.registerStream(
        streamId = streamId,
        connection = connection,
        filePath = file.path,
        fileSize = if (file.size > 0) file.size else -1L,
        mimeType = file.mimeType ?: "video/mp4",
      )
    return Uri.parse(if (withFileName) "$base/${URLEncoder.encode(file.name, "UTF-8")}" else base)
  }

  companion object {
    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )
  }
}
