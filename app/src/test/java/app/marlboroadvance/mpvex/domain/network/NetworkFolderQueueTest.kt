package app.marlboroadvance.mpvex.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Folder queue plan for network playback: videos only, natural name order,
 * current file located in the queue; null when there is nothing to queue.
 */
class NetworkFolderQueueTest {
  private fun entry(
    name: String,
    isDirectory: Boolean = false,
    mime: String? = "video/mp4",
  ) = NetworkFile(name = name, path = "Show/$name", size = 1, isDirectory = isDirectory, mimeType = mime)

  @Test
  fun `plan orders video siblings in natural name order with the current file indexed`() {
    val listing =
      listOf(
        entry("Episode 10"),
        entry("Episode 2"),
        entry("cover.jpg", mime = "image/jpeg"),
        entry("Extras", isDirectory = true),
      )

    val plan = NetworkFolderQueue.plan(listing, currentPath = "Show/Episode 2")!!

    assertEquals(listOf("Episode 2", "Episode 10"), plan.siblings.map { it.name })
    assertEquals(0, plan.currentIndex)
  }

  @Test
  fun `plan is null when the current video is the only one`() {
    val listing =
      listOf(
        entry("Episode 1"),
        entry("Extras", isDirectory = true),
        entry("cover.jpg", mime = "image/jpeg"),
      )

    assertNull(NetworkFolderQueue.plan(listing, currentPath = "Show/Episode 1"))
  }

  @Test
  fun `plan is null when the current file is missing from the listing`() {
    val listing = listOf(entry("Episode 1"), entry("Episode 2"))

    assertNull(NetworkFolderQueue.plan(listing, currentPath = "Show/Episode 3"))
  }
}
