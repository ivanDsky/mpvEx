package app.marlboroadvance.mpvex.domain.playbackstate.repository

import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import kotlinx.coroutines.flow.Flow

interface PlaybackStateRepository {
  suspend fun upsert(playbackState: PlaybackStateEntity)

  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  suspend fun getNetworkPlaybackStates(): List<PlaybackStateEntity>

  fun observeNetworkPlaybackStates(): Flow<List<PlaybackStateEntity>>

  fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>>

  suspend fun clearAllPlaybackStates()

  suspend fun deleteByTitle(mediaTitle: String)

  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )
}
