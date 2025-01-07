package org.videolan.television.viewmodel

import android.app.Application
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.DummyItem
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.moviepedia.database.models.MediaMetadataWithImages
import org.videolan.resources.AndroidDevices
import org.videolan.resources.AppContextProvider
import org.videolan.resources.FAVORITE_TITLE
import org.videolan.resources.HEADER_DIRECTORIES
import org.videolan.resources.HEADER_NETWORK
import org.videolan.resources.HEADER_PERMISSION
import org.videolan.resources.HEADER_SERVER
import org.videolan.resources.HEADER_STREAM
import org.videolan.resources.KEY_CURRENT_MEDIA
import org.videolan.resources.util.getFromMl
import org.videolan.television.ui.FAVORITE_FLAG
import org.videolan.television.ui.MainTvActivity
import org.videolan.television.ui.browser.TVActivity
import org.videolan.television.ui.browser.VerticalGridActivity
import org.videolan.tools.NetworkMonitor
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.tools.getContextWithLocale
import org.videolan.tools.retrieveParent
import org.videolan.vlc.ExternalMonitor
import org.videolan.vlc.gui.DialogActivity
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.askStoragePermission
import org.videolan.vlc.mediadb.models.BrowserFav
import org.videolan.vlc.repository.BrowserFavRepository
import org.videolan.vlc.repository.DirectoryRepository
import org.videolan.vlc.util.convertFavorites
import org.videolan.vlc.util.scanAllowed

class MainTvModel(app: Application) : AndroidViewModel(app), Medialibrary.OnMedialibraryReadyListener,
    Medialibrary.OnDeviceChangeListener {

    val context = getApplication<Application>().getContextWithLocale(AppContextProvider.locale)
    private val medialibrary = Medialibrary.getInstance()
    private val networkMonitor = NetworkMonitor.getInstance(context)
    val settings = Settings.getInstance(context)
    private val showInternalStorage = AndroidDevices.showInternalStorage()
    private val browserFavRepository = BrowserFavRepository.getInstance(context)
    private var updatedFavoriteList: List<MediaWrapper> = listOf()
    var showHistory = false
        private set

    // LiveData
    private val favorites: LiveData<List<BrowserFav>> = browserFavRepository.getFavDao().asLiveData(viewModelScope.coroutineContext)
    val favoritesList: LiveData<List<MediaLibraryItem>> = MutableLiveData()
    val browsers: LiveData<List<MediaLibraryItem>> = MutableLiveData()
    val history: LiveData<List<MediaWrapper>> = MutableLiveData()
    val playlist: LiveData<List<MediaLibraryItem>> = MutableLiveData()

    @OptIn(ObsoleteCoroutinesApi::class)
    private val updateActor = viewModelScope.actor<Unit>(capacity = Channel.CONFLATED) {
        for (action in channel) updateBrowsers()
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val historyActor = viewModelScope.actor<Unit>(capacity = Channel.CONFLATED) {
        for (action in channel) setHistory()
    }

    private val favObserver = Observer<List<BrowserFav>> { list ->
        updatedFavoriteList = convertFavorites(list)
        if (!updateActor.isClosedForSend) updateActor.trySend(Unit)
    }

    init {
        medialibrary.addOnMedialibraryReadyListener(this)
        medialibrary.addOnDeviceChangeListener(this)
        favorites.observeForever(favObserver)
        networkMonitor.connectionFlow.onEach { updateActor.trySend(Unit) }.launchIn(viewModelScope)
        ExternalMonitor.storageEvents.onEach { updateActor.trySend(Unit) }.launchIn(viewModelScope)
    }

    fun refresh() = viewModelScope.launch {
        historyActor.trySend(Unit)
        updateActor.trySend(Unit)
    }

    private suspend fun setHistory() {
        if (!medialibrary.isStarted) return
        val historyEnabled = settings.getBoolean(PLAYBACK_HISTORY, true)
        showHistory = historyEnabled
        if (!historyEnabled) (history as MutableLiveData).value = emptyList()
        else updateHistory()
    }

    suspend fun updateHistory() {
        if (!showHistory) return
        (history as MutableLiveData).value = context.getFromMl {
            history(Medialibrary.HISTORY_TYPE_LOCAL).toMutableList()
                .groupBy { it.uri.retrieveParent() }.map { it.value.first() }
        }
    }

    private suspend fun updateBrowsers() {
        val favList = mutableListOf<MediaLibraryItem>()
        updatedFavoriteList.forEach {
            it.description = it.uri.scheme
            it.addFlags(FAVORITE_FLAG)
            favList.add(it)
        }
        (favoritesList as MutableLiveData).value = favList
        val list = mutableListOf<MediaLibraryItem>()
        val directories = DirectoryRepository.getInstance(context).getMediaDirectoriesList(context).toMutableList()
        if (!showInternalStorage && directories.isNotEmpty()) directories.removeAt(0)
        directories.forEach { if (it.location.scanAllowed()) list.add(it) }

        (browsers as MutableLiveData).value = list
        delay(500L)
    }

    override fun onMedialibraryIdle() {
        refresh()
    }

    override fun onMedialibraryReady() {
        refresh()
    }

    override fun onDeviceChange() {
        refresh()
    }

    override fun onCleared() {
        super.onCleared()
        medialibrary.removeOnMedialibraryReadyListener(this)
        medialibrary.removeOnDeviceChangeListener(this)
        favorites.removeObserver(favObserver)
    }

    fun open(activity: FragmentActivity, item: Any?) {
        when (item) {
            is MediaWrapper -> when (item.type) {
                MediaWrapper.TYPE_DIR -> {
                    val intent = Intent(activity, VerticalGridActivity::class.java)
                    intent.putExtra(MainTvActivity.BROWSER_TYPE, if ("file" == item.uri.scheme) HEADER_DIRECTORIES else HEADER_NETWORK)
                    intent.putExtra(FAVORITE_TITLE, item.title)
                    intent.data = item.uri
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                }

                else -> {
                    val intent = Intent(activity, VerticalGridActivity::class.java)
                    intent.putExtra(MainTvActivity.BROWSER_TYPE, if ("file" == item.uri.scheme) HEADER_DIRECTORIES else HEADER_NETWORK)
                    intent.putExtra(FAVORITE_TITLE, item.title)
                    intent.data = item.uri.retrieveParent()
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra(KEY_CURRENT_MEDIA, item.uri.toString())
                    activity.startActivity(intent)
                }
            }

            is DummyItem -> when (item.id) {
                HEADER_PERMISSION -> activity.askStoragePermission(false, null)
                HEADER_STREAM -> {
                    val intent = Intent(activity, TVActivity::class.java)
                    intent.putExtra(MainTvActivity.BROWSER_TYPE, HEADER_STREAM)
                    activity.startActivity(intent)
                }

                HEADER_SERVER -> activity.startActivity(
                    Intent(activity, DialogActivity::class.java).setAction(DialogActivity.KEY_SERVER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

                else -> {
                    val intent = Intent(activity, VerticalGridActivity::class.java)
                    intent.putExtra(MainTvActivity.BROWSER_TYPE, item.id)
                    activity.startActivity(intent)
                }
            }

            is MediaMetadataWithImages -> {
                item.metadata.mlId?.let {
                    viewModelScope.launch {
                        context.getFromMl {
                            getMedia(it)
                        }.let {
                            val intent = Intent(activity, org.videolan.television.ui.DetailsActivity::class.java)
                            // pass the item information
                            intent.putExtra("media", it)
                            intent.putExtra(
                                "item",
                                org.videolan.television.ui.MediaItemDetails(
                                    it.title,
                                    it.artistName,
                                    it.albumName,
                                    it.location,
                                    it.artworkURL
                                )
                            )
                            activity.startActivity(intent)
                        }
                    }
                }
            }

            is MediaLibraryItem -> org.videolan.television.ui.TvUtil.openAudioCategory(activity, item)
        }
    }

    companion object {
        fun Fragment.getMainTvModel() =
            ViewModelProvider(requireActivity(), Factory(requireActivity().application)).get(MainTvModel::class.java)
    }

    class Factory(private val app: Application) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MainTvModel(app) as T
        }
    }
}
