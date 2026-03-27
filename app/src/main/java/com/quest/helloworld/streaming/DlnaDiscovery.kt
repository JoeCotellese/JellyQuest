package com.quest.helloworld.streaming

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaDiscoverer
import org.videolan.libvlc.MediaList
import org.videolan.libvlc.interfaces.IMedia

/**
 * Discovers DLNA/UPnP media servers on the local network using VLC's MediaDiscoverer.
 * Exposes a browsable tree: servers -> folders -> media items.
 */
class DlnaDiscovery(private val libVLC: LibVLC) {

    private var discoverer: MediaDiscoverer? = null

    private val _servers = MutableStateFlow<List<DlnaServer>>(emptyList())
    val servers: StateFlow<List<DlnaServer>> = _servers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        if (discoverer != null) return

        Log.i(TAG, "Starting UPnP discovery")
        val disc = MediaDiscoverer(libVLC, "upnp")
        discoverer = disc

        disc.setEventListener { event ->
            when (event.type) {
                MediaDiscoverer.Event.Started -> {
                    Log.i(TAG, "UPnP discovery started")
                    _isDiscovering.value = true
                }
                MediaDiscoverer.Event.Ended -> {
                    Log.i(TAG, "UPnP discovery ended")
                    _isDiscovering.value = false
                }
            }
        }

        val started = disc.start()
        Log.i(TAG, "MediaDiscoverer.start() returned: $started, isReleased: ${disc.isReleased}")

        // Poll for server list changes periodically since MediaList.setEventListener is protected
        scheduleRefresh()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        discoverer?.let { disc ->
            disc.stop()
            disc.release()
        }
        discoverer = null
        _servers.value = emptyList()
        _isDiscovering.value = false
    }

    /**
     * Browse into a DLNA server or folder to get its children.
     * Parses the media synchronously and returns sub-items.
     */
    fun browse(media: IMedia): List<DlnaItem> {
        if (media is Media) {
            media.parse(IMedia.Parse.ParseNetwork or IMedia.Parse.FetchNetwork)
        }

        val subItems = media.subItems() ?: return emptyList()
        val items = mutableListOf<DlnaItem>()
        for (i in 0 until subItems.count) {
            val child = subItems.getMediaAt(i)
            val isFolder = child.type == IMedia.Type.Directory
            items.add(
                DlnaItem(
                    title = child.getMeta(IMedia.Meta.Title) ?: "Unknown",
                    uri = child.uri,
                    isFolder = isFolder,
                    media = child,
                )
            )
        }
        subItems.release()
        return items
    }

    private fun scheduleRefresh() {
        handler.postDelayed({
            refreshServerList()
            if (discoverer != null) {
                scheduleRefresh()
            }
        }, 2000)
    }

    private fun refreshServerList() {
        val disc = discoverer ?: return
        val list = disc.mediaList ?: return
        val servers = mutableListOf<DlnaServer>()
        for (i in 0 until list.count) {
            val media = list.getMediaAt(i)
            servers.add(
                DlnaServer(
                    name = media.getMeta(IMedia.Meta.Title) ?: "Unknown Server",
                    uri = media.uri,
                    media = media,
                )
            )
        }
        list.release()
        if (servers.size != _servers.value.size) {
            Log.i(TAG, "Server list updated: ${servers.size} servers found")
            servers.forEach { Log.i(TAG, "  Server: ${it.name} @ ${it.uri}") }
        }
        _servers.value = servers
    }

    companion object {
        private const val TAG = "VirtualMonitor"
    }
}

data class DlnaServer(
    val name: String,
    val uri: Uri,
    val media: IMedia,
)

data class DlnaItem(
    val title: String,
    val uri: Uri,
    val isFolder: Boolean,
    val media: IMedia,
)
