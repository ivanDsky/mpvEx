package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.PinnedFolderDao
import app.marlboroadvance.mpvex.database.entities.PinnedFolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Pin store for "pin a network folder to Home": pins are per connection+path,
 * listed newest-first; a connection's pins are removed with it.
 */
class PinnedFolderRepository(
  private val dao: PinnedFolderDao,
) {
  fun observeAll(): Flow<List<PinnedFolderEntity>> = dao.observeAll()

  fun observeForConnection(connectionId: Long): Flow<List<PinnedFolderEntity>> =
    dao.observeForConnection(connectionId)

  suspend fun pin(
    connectionId: Long,
    path: String,
    name: String,
    pinnedAt: Long = System.currentTimeMillis(),
  ) {
    dao.upsert(PinnedFolderEntity(connectionId, path, name, pinnedAt))
  }

  suspend fun unpin(
    connectionId: Long,
    path: String,
  ) {
    dao.delete(connectionId, path)
  }

  suspend fun deleteForConnection(connectionId: Long) {
    dao.deleteForConnection(connectionId)
  }
}
