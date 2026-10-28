package app.marlboroadvance.mpvex.domain.network

import app.marlboroadvance.mpvex.utils.sort.SortUtils
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import java.util.Locale

/**
 * Builds the folder queue for network playback, mirroring local behavior where
 * playing one video queues its folder siblings in the player.
 */
object NetworkFolderQueue {
  data class Plan(
    val siblings: List<NetworkFile>,
    val currentIndex: Int,
  )

  /**
   * Plan the queue for playing [currentPath] from a folder [listing]: video
   * files only, natural name order. Returns null when there is nothing to
   * queue (the current video is alone, or missing from the listing).
   */
  fun plan(
    listing: List<NetworkFile>,
    currentPath: String,
  ): Plan? {
    val videos =
      listing
        .filter { !it.isDirectory && it.isVideoEntry() }
        .sortedWith { a, b -> SortUtils.NaturalOrderComparator.DEFAULT.compare(a.name, b.name) }

    val index = videos.indexOfFirst { it.path == currentPath }
    if (videos.size <= 1 || index == -1) return null

    return Plan(siblings = videos, currentIndex = index)
  }

  /**
   * Mirror local folder playlists (extension detection, so servers that
   * report no or coarse mime types still queue), plus accept an explicit
   * video mime type for extension-less entries.
   */
  private fun NetworkFile.isVideoEntry(): Boolean =
    FileTypeUtils.isVideoExtension(name.substringAfterLast('.', "").lowercase(Locale.getDefault())) ||
      mimeType?.startsWith("video/") == true
}
