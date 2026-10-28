package app.marlboroadvance.mpvex.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import org.junit.Test

/**
 * Network browser listing order and queue plans: directories first, most
 * recently modified first within each group, natural-name tie-break; the
 * continue plan follows the folder's watch frontier.
 */
class NetworkFolderQueueTest {
  private fun file(
    name: String,
    isDirectory: Boolean = false,
    lastModified: Long = 0,
  ) = NetworkFile(name = name, path = name, size = 1, isDirectory = isDirectory, lastModified = lastModified)

  private fun episode(name: String) =
    NetworkFile(name = name, path = "Show/$name", size = 1, isDirectory = false, mimeType = "video/mp4")

  private fun watched(name: String, finished: Boolean) =
    app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity(
      mediaTitle = "network_1_${"Show/$name".hashCode()}",
      lastPosition = if (finished) 1800 else 600,
      playbackSpeed = 1.0,
      sid = 0,
      subDelay = 0,
      subSpeed = 1.0,
      aid = 0,
      audioDelay = 0,
      timeRemaining = if (finished) 0 else 1200,
      hasBeenWatched = true,
    )

  // ---- ordering (sortNetworkFiles) ----

  @Test
  fun `directories sort before files`() {
    val listing =
      listOf(
        file("video.mp4", isDirectory = false, lastModified = 300),
        file("Shows", isDirectory = true, lastModified = 100),
      )

    val sorted = SortUtils.sortNetworkFiles(listing)

    assertEquals(listOf("Shows", "video.mp4"), sorted.map { it.name })
  }

  @Test
  fun `within a group more recently modified entries come first`() {
    val listing =
      listOf(
        file("Old", isDirectory = true, lastModified = 100),
        file("New", isDirectory = true, lastModified = 200),
        file("old.mp4", isDirectory = false, lastModified = 10),
        file("new.mp4", isDirectory = false, lastModified = 20),
      )

    val sorted = SortUtils.sortNetworkFiles(listing)

    assertEquals(listOf("New", "Old", "new.mp4", "old.mp4"), sorted.map { it.name })
  }

  @Test
  fun `numeric names tie-break naturally`() {
    val listing =
      listOf(
        file("Episode 10", isDirectory = true, lastModified = 100),
        file("Episode 2", isDirectory = true, lastModified = 100),
      )

    val sorted = SortUtils.sortNetworkFiles(listing)

    assertEquals(listOf("Episode 2", "Episode 10"), sorted.map { it.name })
  }

  @Test
  fun `equal timestamps break ties alphabetically`() {
    val listing =
      listOf(
        file("banana", isDirectory = true, lastModified = 100),
        file("Apple", isDirectory = true, lastModified = 100),
        file("cherry", isDirectory = true, lastModified = 100),
      )

    val sorted = SortUtils.sortNetworkFiles(listing)

    assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.name })
  }

  // ---- dedupe (listing normalization) ----

  @Test
  fun `duplicate entries in a listing collapse to the first occurrence`() {
    val listing =
      listOf(
        file("Movies"),
        file("Movies"),
        file("Music"),
      )

    val deduplicated = NetworkRepository.deduplicated(listing)

    assertEquals(listOf(file("Movies"), file("Music")), deduplicated)
  }

  // ---- continue plan (watch frontier) ----

  @Test
  fun `continue index is the latest in-progress episode`() {
    val siblings = listOf(episode("Episode 1"), episode("Episode 2"), episode("Episode 3"), episode("Episode 4"))
    val states =
      mapOf(
        "Show/Episode 1" to watched("Episode 1", finished = true),
        "Show/Episode 2" to watched("Episode 2", finished = true),
        "Show/Episode 3" to watched("Episode 3", finished = false), // in progress
      )

    val index = NetworkFolderQueue.continueIndex(siblings, states) { it.path }

    assertEquals(2, index) // Episode 3
  }

  @Test
  fun `continue index is the episode after the last finished one`() {
    val siblings = listOf(episode("Episode 1"), episode("Episode 2"), episode("Episode 3"))
    val states =
      mapOf(
        "Show/Episode 1" to watched("Episode 1", finished = true),
        "Show/Episode 2" to watched("Episode 2", finished = true),
      )

    val index = NetworkFolderQueue.continueIndex(siblings, states) { it.path }

    assertEquals(2, index) // Episode 3 — next unwatched
  }

  @Test
  fun `continue index is the first episode when nothing was watched`() {
    val siblings = listOf(episode("Episode 1"), episode("Episode 2"))

    assertEquals(0, NetworkFolderQueue.continueIndex(siblings, emptyMap()) { NetworkRecent.playbackKey(1, it.path) })
  }

  @Test
  fun `continue index ignores states from other folders`() {
    val siblings = listOf(episode("Episode 1"), episode("Episode 2"))
    val states =
      mapOf(
        "network_1_${"Other/Episode 1".hashCode()}" to watched("Episode 1", finished = true),
      )

    assertEquals(0, NetworkFolderQueue.continueIndex(siblings, states) { it.path })
  }
}