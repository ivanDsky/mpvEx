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
  /**
   * The folder's video files in queue order (natural name order).
   */
  fun orderedVideos(listing: List<NetworkFile>): List<NetworkFile> =
    listing
      .filter { !it.isDirectory && it.isVideoEntry() }
      .sortedWith { a, b -> SortUtils.NaturalOrderComparator.DEFAULT.compare(a.name, b.name) }

  fun plan(
    listing: List<NetworkFile>,
    currentPath: String,
  ): Plan? {
    val videos = orderedVideos(listing)

    val index = videos.indexOfFirst { it.path == currentPath }
    if (videos.size <= 1 || index == -1) return null

    return Plan(siblings = videos, currentIndex = index)
  }

  /**
   * Index of the episode to continue a folder from, based on saved watch
   * states (keyed by [playbackKeyOf]):
   * - the latest IN-PROGRESS episode (started, not finished) resumes at its
   *   position;
   * - otherwise the first UNWATCHED episode after the watched run plays from
   *   its start;
   * - otherwise (nothing watched, or everything watched) the first episode.
   */
  fun continueIndex(
    siblings: List<NetworkFile>,
    states: Map<String, app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity>,
    playbackKeyOf: (NetworkFile) -> String,
  ): Int {
    data class Progress(
      val index: Int,
      val started: Boolean,
      val finished: Boolean,
    )

    val progress =
      siblings.mapIndexed { index, file ->
        val state = states[playbackKeyOf(file)]
        Progress(
          index = index,
          started = state != null && (state.lastPosition > 0 || state.hasBeenWatched),
          finished = state != null && state.hasBeenWatched && state.timeRemaining <= 0,
        )
      }

    // The latest episode with watch history is where the user left off:
    // still in progress -> resume it; finished -> continue with the next.
    progress.forEach { println("DIAG idx=${it.index} started=${it.started} finished=${it.finished}") }
    val lastStarted = progress.lastOrNull { it.started } ?: return 0
    return if (!lastStarted.finished) {
      lastStarted.index
    } else {
      progress.getOrNull(lastStarted.index + 1)?.index ?: 0
    }
  }

  /**
   * Index of the episode to continue a folder from.
   *
   * Resolution order:
   * 1. the most recently PLAYED sibling (matched by file name against
   *    [recentNames], newest first) — handles watching backwards or jumping
   *    around, which an order-based scan cannot;
   * 2. otherwise the watch frontier ([continueIndex]): the latest in-progress
   *    episode, or the next one after a finished run;
   * 3. otherwise the first episode.
   */
  fun continueTarget(
    siblings: List<NetworkFile>,
    states: Map<String, app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity>,
    playbackKeyOf: (NetworkFile) -> String,
    recentNames: List<String>,
  ): Int {
    fun normalize(name: String) = name.substringBeforeLast('.', name).lowercase()

    recentNames.forEach { recent ->
      val match = siblings.indexOfFirst { normalize(it.name) == normalize(recent) }
      if (match != -1) return match
    }
    return continueIndex(siblings, states, playbackKeyOf)
  }

  /**
   * Index of the episode to resume a folder from: the most recently played
   * sibling (matched by file name against [recentNames], newest first), or
   * the first sibling when nothing in this folder was played yet.
   */
  fun resumeIndex(
    siblings: List<NetworkFile>,
    recentNames: List<String>,
  ): Int {
    fun normalize(name: String) = name.substringBeforeLast('.', name).lowercase()

    recentNames.forEach { recent ->
      val match = siblings.indexOfFirst { normalize(it.name) == normalize(recent) }
      if (match != -1) return match
    }
    return 0
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
