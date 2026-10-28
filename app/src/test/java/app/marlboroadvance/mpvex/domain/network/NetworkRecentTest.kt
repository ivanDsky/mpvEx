package app.marlboroadvance.mpvex.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stable Recently Played identity for network files: round-trips a
 * connection + path through the mpvexnetwork scheme, survives special
 * characters, and rejects foreign URIs.
 */
class NetworkRecentTest {
  @Test
  fun `recent path round-trips connection and file path`() {
    val stored = NetworkRecent.recentPath(connectionId = 3, path = "Shows/Show Name/Episode 2.mp4")

    val ref = NetworkRecent.parse(stored)!!

    assertEquals(3L, ref.connectionId)
    assertEquals("Shows/Show Name/Episode 2.mp4", ref.path)
    assertEquals("Episode 2.mp4", ref.fileName)
  }

  @Test
  fun `path segments with spaces and special characters survive`() {
    val stored = NetworkRecent.recentPath(connectionId = 1, path = "Sci-Fi (2024)/Ep #1 [1080p].mkv")

    val ref = NetworkRecent.parse(stored)!!

    assertEquals("Sci-Fi (2024)/Ep #1 [1080p].mkv", ref.path)
  }

  @Test
  fun `folder prefixes do not collide with same-prefix folders`() {
    val sibling = NetworkRecent.recentPath(connectionId = 1, path = "Movies2/ep1.mp4")
    val folderPrefix = NetworkRecent.recentPath(connectionId = 1, path = "Movies") + "/"

    assertTrue(sibling.startsWith(folderPrefix).not())
  }

  @Test
  fun `playback key matches the player identifier format`() {
    val path = "Movies/Episode 2.mp4"

    assertEquals("network_3_${path.hashCode()}", NetworkRecent.playbackKey(3, path))
  }

  @Test
  fun `non network uris do not parse`() {
    assertNull(NetworkRecent.parse("http://127.0.0.1:1234/1_123/ep1.mp4"))
    assertNull(NetworkRecent.parse("/storage/emulated/0/movie.mp4"))
    assertNull(NetworkRecent.parse("mpvexnetwork://not-a-number/Movies/ep1.mp4"))
  }
}
