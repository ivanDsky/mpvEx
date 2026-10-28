package app.marlboroadvance.mpvex.ui.browser.folderlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository
import app.marlboroadvance.mpvex.domain.media.model.VideoFolder
import app.marlboroadvance.mpvex.domain.network.NetworkFolderQueue
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.repository.MediaFileRepository
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.FoldersPreferences
import app.marlboroadvance.mpvex.ui.browser.base.BaseBrowserViewModel
import app.marlboroadvance.mpvex.utils.media.MediaIdentifier
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import app.marlboroadvance.mpvex.utils.media.MetadataRetrieval
import app.marlboroadvance.mpvex.utils.permission.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class FolderWithNewCount(
  val folder: VideoFolder,
  val newVideoCount: Int = 0,
)

/** A pinned network folder joined with its connection name for the Home shelf. */
data class PinnedNetworkFolder(
  val connectionId: Long,
  val connectionName: String,
  val path: String,
  val name: String,
) {
  /** Stable map key for per-pin cached state. */
  fun progressKey() = "$connectionId:$path"
}

class FolderListViewModel(
  application: Application,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: app.marlboroadvance.mpvex.preferences.BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val pinnedFolderRepository: app.marlboroadvance.mpvex.database.repository.PinnedFolderRepository by inject()
  private val networkRepository: app.marlboroadvance.mpvex.repository.NetworkRepository by inject()
  private val playbackStarter: app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkPlaybackStarter by inject()

  private val _allVideoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  private val _videoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  val videoFolders: StateFlow<List<VideoFolder>> = _videoFolders.asStateFlow()

  /** Titles that have any playback state — changes only when a new file is played. */
  private val playedTitles: StateFlow<Set<String>> =
    playbackStateRepository
      .observeAllPlaybackStates()
      .map { list -> list.map { it.mediaTitle }.toSet() }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

  /**
   * Per-folder "new" badges as a flow: recomputes when folders, preferences,
   * or the set of played titles changes — i.e. live, without resume hooks.
   */
  val foldersWithNewCount: StateFlow<List<FolderWithNewCount>> =
    combine(
      _videoFolders,
      playedTitles,
      appearancePreferences.showUnplayedOldVideoLabel.changes(),
      appearancePreferences.unplayedOldVideoDays.changes(),
    ) { folders, played, showLabel, thresholdDays ->
      if (!showLabel) return@combine folders.map { FolderWithNewCount(it, 0) }
      val thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L
      val now = System.currentTimeMillis()
      folders.map { folder ->
        val videos =
          runCatching {
            app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosInFolder(getApplication(), folder.bucketId)
          }.getOrDefault(emptyList())
        val newCount =
          videos.count { video ->
            val age = now - (video.dateModified * 1000)
            age <= thresholdMillis && MediaIdentifier.forLocalPath(video.path) !in played
          }
        FolderWithNewCount(folder, newCount)
      }
    }.flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _pinnedNetworkFolders = MutableStateFlow<List<PinnedNetworkFolder>>(emptyList())
  val pinnedNetworkFolders: StateFlow<List<PinnedNetworkFolder>> = _pinnedNetworkFolders.asStateFlow()

  /** Per-pin position ("i of n") + continue-target path, keyed "connectionId:path". */
  data class PinProgress(
    val currentIndex: Int,
    val totalCount: Int,
    val currentPath: String?,
  )

  /** One listing per pin (fetched once); everything else updates reactively. */
  private val _pinListings = MutableStateFlow<Map<String, List<app.marlboroadvance.mpvex.domain.network.NetworkFile>>>(emptyMap())

  /**
   * Watch progress for network files, keyed by playback key. A Room flow:
   * the player's position saves land here and the shelf recomputes — no
   * resume-time refresh.
   */
  val networkStates: StateFlow<Map<String, app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity>> =
    playbackStateRepository
      .observeNetworkPlaybackStates()
      .map { states -> states.associateBy { it.mediaTitle } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  /**
   * Shelf state as a Room-driven flow: listings (fetched once per pin) ×
   * playback states × recently-played history. Watching an episode moves the
   * continue target and the i/n badge without any lifecycle hook.
   */
  val pinnedProgress: StateFlow<Map<String, PinProgress>> =
    combine(
      _pinnedNetworkFolders,
      _pinListings,
      networkStates,
      app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps.observeRecentlyPlayed(limit = 50),
    ) { folders, listings, states, recents ->
      folders.associate { folder ->
        val videos = listings[folder.progressKey()].orEmpty()
        val progress =
          if (videos.isEmpty()) {
            PinProgress(0, 0, null)
          } else {
            val folderPrefix =
              app.marlboroadvance.mpvex.domain.network.NetworkRecent
                .recentPath(folder.connectionId, folder.path) + "/"
            val scopedRecents =
              recents
                .filter { it.filePath.startsWith(folderPrefix) }
                .map { it.fileName }
            val index =
              NetworkFolderQueue.continueTarget(videos, states, { file ->
                app.marlboroadvance.mpvex.domain.network.NetworkRecent.playbackKey(folder.connectionId, file.path)
              }, scopedRecents)
            PinProgress(index + 1, videos.size, videos[index].path)
          }
        folder.progressKey() to progress
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  // Only show loading on fresh install (when there's no cached data)
  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  // Track if initial load has completed to prevent empty state flicker
  private val _hasCompletedInitialLoad = MutableStateFlow(false)
  val hasCompletedInitialLoad: StateFlow<Boolean> = _hasCompletedInitialLoad.asStateFlow()

  // Track if folders were deleted leaving list empty
  private val _foldersWereDeleted = MutableStateFlow(false)
  val foldersWereDeleted: StateFlow<Boolean> = _foldersWereDeleted.asStateFlow()

  // Track previous folder count to detect if all folders were deleted
  private var previousFolderCount = 0

  /*
   * TRACKING LOADING STATE
   */
  private val _scanStatus = MutableStateFlow<String?>(null)
  val scanStatus: StateFlow<String?> = _scanStatus.asStateFlow()

  private val _isEnriching = MutableStateFlow(false)
  val isEnriching: StateFlow<Boolean> = _isEnriching.asStateFlow()

  // Track the current scan job to prevent concurrent scans
  private var currentScanJob: Job? = null

  companion object {
    private const val TAG = "FolderListViewModel"

    fun factory(application: Application) =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderListViewModel(application) as T
      }
  }

  init {
    // Pinned network folders for the Home shelf (newest pin first; the DAO orders)
    viewModelScope.launch {
      combine(
        pinnedFolderRepository.observeAll(),
        networkRepository.getAllConnections(),
      ) { pins, connections ->
        val byId = connections.associateBy { it.id }
        pins.mapNotNull { pin ->
          byId[pin.connectionId]?.let { connection ->
            PinnedNetworkFolder(connection.id, connection.name, pin.path, pin.name)
          }
        }
      }.collect { folders ->
        _pinnedNetworkFolders.value = folders
        // Fetch each pin's listing once (the only network access here);
        // i/n + continue target then follow Room updates automatically.
        folders
          .filter { it.progressKey() !in _pinListings.value }
          .forEach { folder ->
            viewModelScope.launch(Dispatchers.IO) {
              val connection = networkRepository.getConnectionById(folder.connectionId)
              val listing =
                connection?.let { networkRepository.listFiles(connection, folder.path).getOrNull() }
                  ?.let { NetworkFolderQueue.orderedVideos(it) }
              if (listing == null) return@launch // unreachable: retried on the next pins emission
              _pinListings.update { it + (folder.progressKey() to listing) }
              extractFirstFrameIfNeeded(folder, listing)
            }
          }
      }
    }

    // Load cached folders instantly for immediate display
    val hasCachedData = loadCachedFolders()

    // If no cached data (first launch), scan immediately. Otherwise defer to not slow down app launch
    if (!hasCachedData) {
      loadVideoFolders()
    } else {
      viewModelScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.delay(2000) // Wait 2 seconds before refreshing
        loadVideoFolders()
      }
    }

    // Refresh folders on global media library changes
    viewModelScope.launch(Dispatchers.IO) {
      MediaLibraryEvents.changes.collectLatest {
        // Clear cache when media library changes
        MediaFileRepository.clearCache()
        loadVideoFolders()
      }
    }

    // Filter folders based on blacklist
    viewModelScope.launch {
      combine(_allVideoFolders, foldersPreferences.blacklistedFolders.changes()) { folders, blacklist ->
        folders.filter { folder -> folder.path !in blacklist }
      }.collectLatest { filteredFolders ->
        // Check if folders became empty after having folders
        if (previousFolderCount > 0 && filteredFolders.isEmpty()) {
          _foldersWereDeleted.value = true
          Log.d(TAG, "Folders became empty (had $previousFolderCount folders before)")
        } else if (filteredFolders.isNotEmpty()) {
          // Reset flag if folders now exist
          _foldersWereDeleted.value = false
        }

        // Update previous count
        previousFolderCount = filteredFolders.size

        _videoFolders.value = filteredFolders

        // Save to cache for next app launch (save unfiltered list)
        saveFoldersToCache(_allVideoFolders.value)

        // Preload video cache in background so tapping any folder is instant 0ms
        for (folder in filteredFolders) {
          app.marlboroadvance.mpvex.repository.VideoStatCache.preload(getApplication(), folder.bucketId)
        }
      }
    }
  }

  /** History file names inside one pinned folder, newest first. */
  private suspend fun folderScopedRecentNames(folder: PinnedNetworkFolder): List<String> {
    val folderPrefix =
      app.marlboroadvance.mpvex.domain.network.NetworkRecent
        .recentPath(folder.connectionId, folder.path) + "/"
    return app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
      .getRecentlyPlayed(limit = 50)
      .filter { it.filePath.startsWith(folderPrefix) }
      .map { it.fileName }
  }

  /**
   * A continue target with no stored frame was never played on this device:
   * extract its first frame once; the put() broadcast updates every observer.
   */
  private fun extractFirstFrameIfNeeded(
    folder: PinnedNetworkFolder,
    videos: List<app.marlboroadvance.mpvex.domain.network.NetworkFile>,
  ) {
    if (videos.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      val index =
        NetworkFolderQueue.continueTarget(videos, networkStates.value, { file ->
          app.marlboroadvance.mpvex.domain.network.NetworkRecent.playbackKey(folder.connectionId, file.path)
        }, folderScopedRecentNames(folder))
      val target = videos[index]
      val cache = app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkThumbnailCache.get(getApplication())
      if (cache.cached(folder.connectionId, target.path) == null) {
        playbackStarter.extractFrameById(folder.connectionId, target)
      }
    }
  }

  /**
   * Continue a pinned folder's playlist: play the most recently played
   * episode at its saved position, with the rest of the folder queued.
   * Long-press opens the folder browser instead; unpin lives there.
   */
  fun continuePinnedPlaylist(folder: PinnedNetworkFolder) {
    viewModelScope.launch {
      val connection = networkRepository.getConnectionById(folder.connectionId)
      // Prefer the cached listing; fall back to the network when missing.
      val cachedListing = _pinListings.value[folder.progressKey()]
      val listing =
        cachedListing
          ?: connection?.let { networkRepository.listFiles(connection, folder.path).getOrNull() }
            ?.let { NetworkFolderQueue.orderedVideos(it) }
      if (connection == null || listing.isNullOrEmpty()) {
        Log.e(TAG, "Cannot continue playlist for ${folder.name}: folder not reachable")
        return@launch
      }
      val videos = if (cachedListing != null) cachedListing else listing

      val index =
        NetworkFolderQueue.continueTarget(videos, networkStates.value, { file ->
          app.marlboroadvance.mpvex.domain.network.NetworkRecent.playbackKey(folder.connectionId, file.path)
        }, folderScopedRecentNames(folder))
      val target = videos[index]
      val fullListing = connection.let { networkRepository.listFiles(connection, folder.path).getOrNull() }
      playbackStarter
        .play(folder.connectionId, target, fullListing ?: videos)
        .onFailure { e -> Log.e(TAG, "Failed to start playback for ${folder.name}", e) }
    }
  }

  private fun loadCachedFolders(): Boolean {
    var hasCachedData = false
    val prefs =
      getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
    val cachedJson = prefs.getString("folders", null)

    if (cachedJson != null) {
      try {
        // Parse JSON and restore folders
        val folders = parseFoldersFromJson(cachedJson)
        if (folders.isNotEmpty()) {
          Log.d(TAG, "Loaded ${folders.size} folders from cache instantly")
          hasCachedData = true
          viewModelScope.launch(Dispatchers.IO) {
            _allVideoFolders.value = folders
            _hasCompletedInitialLoad.value = true
            for (folder in folders) {
              app.marlboroadvance.mpvex.repository.VideoStatCache.preload(getApplication(), folder.bucketId)
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading cached folders", e)
      }
    }

    return hasCachedData
  }

  private fun saveFoldersToCache(folders: List<VideoFolder>) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val prefs =
          getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
        val json = serializeFoldersToJson(folders)
        prefs.edit().putString("folders", json).apply()
        Log.d(TAG, "Saved ${folders.size} folders to cache")
      } catch (e: Exception) {
        Log.e(TAG, "Error saving folders to cache", e)
      }
    }
  }

  private fun serializeFoldersToJson(folders: List<VideoFolder>): String {
    // Simple JSON serialization
    return folders.joinToString(separator = "|") { folder ->
      "${folder.bucketId}::${folder.name}::${folder.path}::${folder.videoCount}::${folder.totalSize}::${folder.totalDuration}::${folder.lastModified}"
    }
  }

  private fun parseFoldersFromJson(json: String): List<VideoFolder> {
    return try {
      json.split("|").mapNotNull { item ->
        val parts = item.split("::")
        if (parts.size == 7) {
          VideoFolder(
            bucketId = parts[0],
            name = parts[1],
            path = parts[2],
            videoCount = parts[3].toIntOrNull() ?: 0,
            totalSize = parts[4].toLongOrNull() ?: 0L,
            totalDuration = parts[5].toLongOrNull() ?: 0L,
            lastModified = parts[6].toLongOrNull() ?: 0L,
          )
        } else null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing cached folders", e)
      emptyList()
    }
  }

  override fun refresh() {
    Log.d(TAG, "Refreshing folder list")
    _isLoading.value = true
    MediaFileRepository.clearCache()
    viewModelScope.launch(Dispatchers.IO) {
      triggerMediaScan()
      loadVideoFolders()
    }
  }
  
  /**
   * Trigger a comprehensive media scan to update MediaStore
   */
  private suspend fun triggerMediaScan() = withContext(Dispatchers.IO) {
    try {
      val externalStorage = android.os.Environment.getExternalStorageDirectory()
      
      android.media.MediaScannerConnection.scanFile(
        getApplication(),
        arrayOf(externalStorage.absolutePath),
        null, // Let MediaScanner detect all media types
      ) { path, uri ->
        Log.d(TAG, "Media scan completed for: $path -> $uri")
      }
      
      Log.d(TAG, "Triggered comprehensive media scan")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to trigger media scan", e)
    }
  }



  /**
   * Scans the filesystem recursively to find all folders containing videos.
   * Uses optimized parallel scanning with complete metadata (including duration)
   * to provide fast, non-flickering results.
   */
  private fun loadVideoFolders() {
    // Cancel any previous scan to prevent concurrent execution
    currentScanJob?.cancel()
    
    currentScanJob = viewModelScope.launch(Dispatchers.IO) {
      try {
        // Show loading state if no folders yet
        val hasExistingData = _allVideoFolders.value.isNotEmpty()
        
        if (!hasExistingData) {
          _isLoading.value = true
          _scanStatus.value = "Scanning storage..."
        }

        // Capture current state for comparison
        val currentFoldersMap = _allVideoFolders.value.associateBy { it.bucketId }

        // PHASE 1: Fast Parallel Scan (always show all folders)
        val fastFolders = app.marlboroadvance.mpvex.repository.MediaFileRepository
          .getAllVideoFoldersFast(
            context = getApplication(),
            onProgress = { count ->
              // Only show progress if we don't have existing data (silent refresh)
              if (!hasExistingData) {
                _scanStatus.value = "Found $count folders..."
              }
            }
          )

        Log.d(TAG, "Fast scan completed: found ${fastFolders.size} folders")

        // EDGE CASE: Empty result when we had data AND storage permission was actually revoked
        val hasPermission = PermissionUtils.hasStoragePermission(getApplication<Application>())
        if (fastFolders.isEmpty() && hasExistingData && !hasPermission) {
             Log.w(TAG, "Scan returned empty because storage permission was revoked")
             // Keep existing data, don't clear
             _isLoading.value = false
             _scanStatus.value = null
             return@launch
        }

        // MERGE STRATEGY:
        var needsEnrichment = false
        
        val mergedFolders = fastFolders.map { fastFolder ->
             val cached = currentFoldersMap[fastFolder.bucketId]
             // Check if cached data is valid, matches
             val cachedIsEnriched = cached != null && (
                 cached.videoCount == 0 || // Empty folders don't need duration
                 cached.totalDuration > 0   // Has been enriched
             )
             
             if (cached != null && 
                 cached.videoCount == fastFolder.videoCount && 
                 cached.lastModified == fastFolder.lastModified &&
                 cached.videoCount >= 0 && 
                 fastFolder.videoCount >= 0 &&
                 cachedIsEnriched) {
                 cached
             } else {
                 needsEnrichment = true
                 fastFolder
             }
        }

        // Immediate update with MERGED data
        if (mergedFolders.isNotEmpty()) {
            _allVideoFolders.value = mergedFolders
             _isLoading.value = false 
             _hasCompletedInitialLoad.value = true
        } else {
             // Legitimate empty result (no videos on device)
             _allVideoFolders.value = emptyList()
             _isLoading.value = false
             _hasCompletedInitialLoad.value = true
             _scanStatus.value = null
             return@launch
        }

        // OPTIMIZATION: Skip enrichment if data is up-to-date OR if duration chip is disabled
        val needsDurationEnrichment = needsEnrichment && MetadataRetrieval.isFolderMetadataNeeded(browserPreferences)
        
        if (!needsDurationEnrichment) {
             if (!needsEnrichment) {
                 Log.d(TAG, "Data up to date, skipping enrichment")
             } else {
                 Log.d(TAG, "Duration chip disabled, skipping metadata extraction")
             }
             _scanStatus.value = null
             return@launch
        }

        // PHASE 2: Background Enrichment (only if duration chip is enabled)
        _isEnriching.value = true
        _scanStatus.value = "Processing metadata..."
        
        val enrichedFolders = MetadataRetrieval.enrichFoldersIfNeeded(
            context = getApplication(),
            folders = mergedFolders,
            browserPreferences = browserPreferences,
            metadataCache = metadataCache,
            onProgress = { processed, total ->
               _scanStatus.value = "Processing metadata $processed/$total"
            }
          )

        Log.d(TAG, "Enrichment completed")
        _allVideoFolders.value = enrichedFolders

      } catch (e: kotlinx.coroutines.CancellationException) {
        // Job was cancelled (new scan started), this is expected
        Log.d(TAG, "Scan cancelled (new scan started)")
        throw e // Re-throw to properly cancel the coroutine
      } catch (e: Exception) {
        Log.e(TAG, "Error loading video folders", e)
        // EDGE CASE: Preserve existing data on error if we have it
        if (_allVideoFolders.value.isEmpty()) {
             _allVideoFolders.value = emptyList()
        }
        // If we have merged data from Phase 1, it's already in _allVideoFolders
        // So we don't overwrite it here
        _hasCompletedInitialLoad.value = true
      } finally {
        _isLoading.value = false
        _isEnriching.value = false
        _scanStatus.value = null
      }
    }
  }


}
