package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.PinnedFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedFolderDao {
  @Query("SELECT * FROM pinned_folders ORDER BY pinnedAt DESC")
  fun observeAll(): Flow<List<PinnedFolderEntity>>

  @Query("SELECT * FROM pinned_folders WHERE connectionId = :connectionId ORDER BY pinnedAt DESC")
  fun observeForConnection(connectionId: Long): Flow<List<PinnedFolderEntity>>

  @Upsert
  suspend fun upsert(pin: PinnedFolderEntity)

  @Query("DELETE FROM pinned_folders WHERE connectionId = :connectionId AND path = :path")
  suspend fun delete(connectionId: Long, path: String)

  @Query("DELETE FROM pinned_folders WHERE connectionId = :connectionId")
  suspend fun deleteForConnection(connectionId: Long)
}
