package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.database.repository.PinnedFolderRepository
import app.marlboroadvance.mpvex.domain.network.NetworkFolderQueue
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
   * Register a file with the streaming proxy and return its playback URI.
   * When [withFileName] is set, the file name is appended to the path so
   * player titles derived from the URL show it; the proxy routes on the
   * first path segment only.
   */
  private fun proxyUri(
    connection: NetworkConnection,
    file: NetworkFile,
    streamId: String,
    withFileName: Boolean = false,
  ): Uri {
    val proxy = NetworkStreamingProxy.getInstance()
    val base =
      proxy.registerStream(
        streamId = streamId,
        connection = connection,
        filePath = file.path,
        fileSize = file.size,
        mimeType = file.mimeType ?: "video/mp4",
      )
    return Uri.parse(if (withFileName) "$base/${URLEncoder.encode(file.name, "UTF-8")}" else base)
  }

  /**
   * Play a video file
   */
  fun playVideo(file: NetworkFile) {
    viewModelScope.launch {
      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS

        // Queue the rest of the folder like local playback does: list the
        // parent, order videos naturally, register each sibling once.
        var queue: List<Uri> = emptyList()
        var queueIndex = 0
        if (useProxy) {
          val parentPath = file.path.substringBeforeLast("/", missingDelimiterValue = "")
          val listing = repository.listFiles(connection, parentPath).getOrNull()
          val plan = listing?.let { NetworkFolderQueue.plan(it, file.path) }
          if (plan != null) {
            val proxy = NetworkStreamingProxy.getInstance()
            val timestamp = System.currentTimeMillis()
            queue =
              plan.siblings.mapIndexed { i, sibling ->
                proxyUri(connection, sibling, "${connectionId}_${timestamp}_$i", withFileName = true)
              }
            queueIndex = plan.currentIndex
          }
        }

        val uri =
          when {
            queue.isNotEmpty() -> queue[queueIndex]
            useProxy -> proxyUri(connection, file, "${connectionId}_${System.currentTimeMillis()}")
            else -> {
              NetworkStreamingProvider.setConnection(connectionId, connection)
              NetworkStreamingProvider.getUri(application, connectionId, file.path)
            }
          }

        // Launch the player
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("launch_source", "network_stream")
        intent.putExtra("title", file.name)
        intent.putExtra("filename", file.name)
        // Pass the original network file path for stable media identifier (position saving)
        intent.putExtra("network_file_path", file.path)
        intent.putExtra("network_connection_id", connectionId)
        if (queue.isNotEmpty()) {
          intent.putParcelableArrayListExtra("playlist", ArrayList(queue))
          intent.putExtra("playlist_index", queueIndex)
        }
        intent.setDataAndType(uri, file.mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        application.startActivity(intent)
      } catch (e: Exception) {
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
