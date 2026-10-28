package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.repository.PinnedFolderRepository
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.domain.network.NetworkRecent
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for browsing files on a network share
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkBrowserViewModel(
  private val application: Application,
  private val connectionId: Long,
  private val currentPath: String,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()

  private val pinnedFolderRepository: PinnedFolderRepository by inject()
  private val playbackStarter: NetworkPlaybackStarter by inject()
  private val thumbnailCache: NetworkThumbnailCache by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private val _files = MutableStateFlow<List<NetworkFile>>(emptyList())
  val files: StateFlow<List<NetworkFile>> = _files.asStateFlow()

  private val _pinnedPaths = MutableStateFlow<Set<String>>(emptySet())
  val pinnedPaths: StateFlow<Set<String>> = _pinnedPaths.asStateFlow()

  init {
    viewModelScope.launch {
      pinnedFolderRepository
        .observeForConnection(connectionId)
        .map { pins -> pins.map { it.path }.toSet() }
        .collect { _pinnedPaths.value = it }
    }
  }

  /**
   * Watch progress for network files, keyed by [NetworkRecent.playbackKey].
   * A Room flow: every position save in the player lands here and every
   * progress bar recomposes — no resume-time refresh.
   */
  val playbackStates: StateFlow<Map<String, PlaybackStateEntity>> =
    playbackStateRepository
      .observeNetworkPlaybackStates()
      .map { states -> states.associateBy { it.mediaTitle } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  /**
   * Saved resume position of an IN-PROGRESS network file in seconds, or null
   * when unwatched or fully watched (their thumbnails stay at start-of-file).
   */

  /** Watched fraction (0f..1f exclusive) of a network file, or null when unwatched/finished. */
  fun progressFraction(file: NetworkFile): Float? =
    progressFractionOf(playbackStates.value, connectionId, file)

  /** Cached frame, or extract the first frame through the starter. */
  suspend fun thumbnailFor(file: NetworkFile): android.graphics.Bitmap? {
    thumbnailCache.cached(connectionId, file.path)?.let { return it }
    return playbackStarter.extractFrameById(connectionId, file)
  }

  /** Pin a folder to Home, or unpin it if already pinned. */
  fun togglePin(file: NetworkFile) {
    viewModelScope.launch {
      if (file.path in _pinnedPaths.value) {
        pinnedFolderRepository.unpin(connectionId, file.path)
      } else {
        pinnedFolderRepository.pin(connectionId, file.path, file.name)
      }
    }
  }

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            _files.value = SortUtils.sortNetworkFiles(fileList)
          }
          .onFailure { e ->
            _error.value = e.message ?: "Unknown error"
          }
      } catch (e: Exception) {
        _error.value = e.message ?: "Unknown error"
      } finally {
        _isLoading.value = false
      }
    }
  }




  /**
   * Play a video file
   */
  fun playVideo(file: NetworkFile) {
    viewModelScope.launch {
      playbackStarter
        .play(connectionId, file)
        .onFailure { e ->
          Log.e(TAG, "Error playing video", e)
          _error.value = e.message ?: "Unknown error"
        }
    }
  }

  companion object {
    private const val TAG = "NetworkBrowserVM"

    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )

    fun factory(
      application: Application,
      connectionId: Long,
      currentPath: String,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkBrowserViewModel(application, connectionId, currentPath)
        }
      }
  }
}

/** Watched fraction from a states map (shared by the browser screen rows). */
fun progressFractionOf(
  states: Map<String, app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity>,
  connectionId: Long,
  file: NetworkFile,
): Float? {
  val state = states[app.marlboroadvance.mpvex.domain.network.NetworkRecent.playbackKey(connectionId, file.path)] ?: return null
  if (state.lastPosition <= 0) return null
  val total = state.lastPosition + state.timeRemaining
  if (total <= 0) return null
  val fraction = (state.lastPosition.toFloat() / total).coerceIn(0f, 1f)
  // Any started episode shows its bar — even a thin one — so "I'm in the
  // middle" is visible; fully watched episodes stay clean.
  return fraction.takeIf { it < 0.99f }
}
