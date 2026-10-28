package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import kotlinx.coroutines.flow.Flow

class PlaybackStateRepositoryImpl(
  private val database: MpvExDatabase,
) : PlaybackStateRepository {
  override suspend fun upsert(playbackState: PlaybackStateEntity) {
    database.videoDataDao().upsert(playbackState)
  }

  override suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity? =
    database.videoDataDao().getVideoDataByTitle(mediaTitle)

  override suspend fun getNetworkPlaybackStates(): List<PlaybackStateEntity> =
    database.videoDataDao().getNetworkPlaybackStates()

  override fun observeNetworkPlaybackStates(): Flow<List<PlaybackStateEntity>> =
    database.videoDataDao().observeNetworkPlaybackStates()

  override fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>> =
    database.videoDataDao().observeAllPlaybackStates()

  override suspend fun clearAllPlaybackStates() {
    database.videoDataDao().clearAllPlaybackStates()
  }

  override suspend fun deleteByTitle(mediaTitle: String) {
    database.videoDataDao().deleteByTitle(mediaTitle)
  }

  override suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  ) {
    database.videoDataDao().updateMediaTitle(oldTitle, newTitle)
  }
}
