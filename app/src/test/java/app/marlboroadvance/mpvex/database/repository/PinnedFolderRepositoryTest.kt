package app.marlboroadvance.mpvex.database.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.marlboroadvance.mpvex.database.MpvExDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam: the pin store for "pin a network folder to Home".
 *
 * Pins are per connection+path, listed newest-first, and a connection's pins
 * are removed when the connection is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PinnedFolderRepositoryTest {
  private lateinit var db: MpvExDatabase
  private lateinit var repository: PinnedFolderRepository

  @Before
  fun setUp() {
    db =
      Room
        .inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          MpvExDatabase::class.java,
        ).allowMainThreadQueries()
        .build()
    repository = PinnedFolderRepository(db.pinnedFolderDao())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `pinning folders lists them newest first`() = runTest {
    repository.pin(connectionId = 1, path = "Shows", name = "Shows", pinnedAt = 100)
    repository.pin(connectionId = 1, path = "Movies", name = "Movies", pinnedAt = 200)

    val pins = repository.observeAll().first()

    assertEquals(listOf("Movies", "Shows"), pins.map { it.name })
  }

  @Test
  fun `pinning the same folder twice does not duplicate it`() = runTest {
    repository.pin(connectionId = 1, path = "Shows", name = "Shows", pinnedAt = 100)
    repository.pin(connectionId = 1, path = "Shows", name = "Shows", pinnedAt = 200)

    val pins = repository.observeAll().first()

    assertEquals(1, pins.size)
    assertEquals(200, pins.single().pinnedAt)
  }

  @Test
  fun `unpinning removes the pin`() = runTest {
    repository.pin(connectionId = 1, path = "Shows", name = "Shows", pinnedAt = 100)

    repository.unpin(connectionId = 1, path = "Shows")

    assertTrue(repository.observeAll().first().isEmpty())
  }

  @Test
  fun `deleting a connection removes only its pins`() = runTest {
    repository.pin(connectionId = 1, path = "Shows", name = "Shows", pinnedAt = 100)
    repository.pin(connectionId = 2, path = "Films", name = "Films", pinnedAt = 200)

    repository.deleteForConnection(connectionId = 1)

    val pins = repository.observeAll().first()
    assertEquals(listOf("Films"), pins.map { it.name })
  }
}
