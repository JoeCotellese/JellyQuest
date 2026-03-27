package com.quest.helloworld.streaming

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * StreamSource backed by VLC's MediaPlayer for DLNA/UPnP media playback.
 * Also works for any URI VLC supports (RTSP, HTTP, file://, etc.).
 */
class DlnaSource(private val libVLC: LibVLC) : StreamSource {

    private var mediaPlayer: MediaPlayer? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    override val mediaInfo: StateFlow<MediaInfo?> = _mediaInfo.asStateFlow()

    private var currentSurface: Surface? = null

    override fun connect(uri: String) {
        disconnect()

        _connectionState.value = ConnectionState.CONNECTING

        val player = MediaPlayer(libVLC)
        mediaPlayer = player

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    _connectionState.value = ConnectionState.PLAYING
                    // Update media info from video track
                    val vt = player.currentVideoTrack
                    if (vt != null) {
                        _mediaInfo.value = MediaInfo(
                            title = uri.substringAfterLast('/'),
                            width = vt.width,
                            height = vt.height,
                        )
                    }
                }
                MediaPlayer.Event.Paused -> {
                    _connectionState.value = ConnectionState.PAUSED
                }
                MediaPlayer.Event.Stopped,
                MediaPlayer.Event.EndReached -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                MediaPlayer.Event.EncounteredError -> {
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }

        // Attach surface if we already have one
        currentSurface?.let { surface ->
            val vout = player.vlcVout
            vout.setVideoSurface(surface, null)
            vout.attachViews()
        }

        val media = Media(libVLC, android.net.Uri.parse(uri))
        player.media = media
        media.release()
        player.play()
    }

    override fun disconnect() {
        mediaPlayer?.let { player ->
            player.stop()
            player.vlcVout.detachViews()
            player.release()
        }
        mediaPlayer = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _mediaInfo.value = null
    }

    override fun attachSurface(surface: Surface) {
        currentSurface = surface
        mediaPlayer?.let { player ->
            val vout = player.vlcVout
            vout.setVideoSurface(surface, null)
            vout.attachViews()
        }
    }

    override fun detachSurface() {
        mediaPlayer?.vlcVout?.detachViews()
        currentSurface = null
    }

    override fun play() {
        mediaPlayer?.play()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }
}
