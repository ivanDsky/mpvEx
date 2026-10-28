package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity

/**
 * A network folder pinned to the Home tab.
 * Unique per connection + path; a connection's pins are removed with it.
 */
@Entity(tableName = "pinned_folders", primaryKeys = ["connectionId", "path"])
data class PinnedFolderEntity(
  val connectionId: Long,
  val path: String,
  val name: String,
  val pinnedAt: Long,
)
