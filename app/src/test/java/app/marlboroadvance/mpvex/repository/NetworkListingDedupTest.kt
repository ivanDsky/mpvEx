package app.marlboroadvance.mpvex.repository

import app.marlboroadvance.mpvex.domain.network.NetworkFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam: raw server entries -> safe, deduplicated listing.
 *
 * Some WebDAV/SMB/FTP servers return two entries with the same name in a single
 * directory listing. The browser renders listings in a LazyColumn keyed by path,
 * so a duplicate path crashes the app ("Key was already used").
 */
class NetworkListingDedupTest {
  private fun file(name: String, path: String = name) =
    NetworkFile(name = name, path = path, size = 1, isDirectory = true)

  @Test
  fun `duplicate entries in a listing collapse to the first occurrence`() {
    val listing =
      listOf(
        file("Movies"),
        file("Movies"), // server returned the same entry twice
        file("Music"),
      )

    val deduplicated = NetworkRepository.deduplicated(listing)

    assertEquals(listOf(file("Movies"), file("Music")), deduplicated)
  }

  @Test
  fun `unique entries keep their original order`() {
    val listing = listOf(file("C"), file("A"), file("B"))

    assertEquals(listing, NetworkRepository.deduplicated(listing))
  }

  @Test
  fun `entries sharing a name but not a path are both kept`() {
    val listing =
      listOf(
        file("Season 1", path = "Show/Season 1"),
        file("Season 1", path = "Other Show/Season 1"),
      )

    assertEquals(listing, NetworkRepository.deduplicated(listing))
  }
}
