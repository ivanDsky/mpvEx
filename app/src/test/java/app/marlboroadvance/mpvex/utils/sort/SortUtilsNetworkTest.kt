package app.marlboroadvance.mpvex.utils.sort

import app.marlboroadvance.mpvex.domain.network.NetworkFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam: network browser listing order.
 *
 * The browser shows directories first, each group with the most recently
 * modified entries first; equal timestamps break ties alphabetically so the
 * order is deterministic.
 */
class SortUtilsNetworkTest {
  private fun file(
    name: String,
    isDirectory: Boolean,
    lastModified: Long,
  ) = NetworkFile(name = name, path = name, size = 1, isDirectory = isDirectory, lastModified = lastModified)

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
}
