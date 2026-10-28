package app.marlboroadvance.mpvex.domain.network

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Stable identity for a network file in Recently Played. The proxy URL that
 * network playback actually streams from is ephemeral (per-launch port and
 * stream id), so history stores this scheme instead and replay resolves it
 * back to a connection + file path.
 */
object NetworkRecent {
  const val SCHEME = "mpvexnetwork"

  // Intent extras carried by network launches (producer: NetworkPlaybackStarter,
  // readers: PlayerActivity). Kept here so the literals live in one place.
  const val EXTRA_NETWORK_FILE_PATH = "network_file_path"
  const val EXTRA_NETWORK_CONNECTION_ID = "network_connection_id"

  /**
   * "mpvexnetwork://<connectionId>/<a/b/file.mkv>" for a network file at
   * path "a/b/file.mkv" on that connection. Path segments are encoded
   * individually; slashes stay slashes so folder prefixes are stable.
   */
  fun recentPath(
    connectionId: Long,
    path: String,
  ): String {
    val encoded =
      path
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") { segment ->
          URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    return "$SCHEME://$connectionId/$encoded"
  }

  data class Ref(
    val connectionId: Long,
    val path: String,
    val fileName: String,
  )

  /** Inverse of [recentPath]; null when [filePath] is not a network-recent URI. */
  fun parse(filePath: String): Ref? =
    runCatching {
      val uri = URI(filePath)
      if (uri.scheme?.lowercase() != SCHEME || uri.host == null) return@runCatching null
      val connectionId = uri.host.toLongOrNull() ?: return@runCatching null
      val decoded =
        uri.path
          ?.trim('/')
          ?.split('/')
          ?.joinToString("/") { URLDecoder.decode(it, "UTF-8") }
          ?: return@runCatching null
      if (decoded.isEmpty()) return@runCatching null
      Ref(connectionId, decoded, decoded.substringAfterLast('/'))
    }.getOrNull()
}
