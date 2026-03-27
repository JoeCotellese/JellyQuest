package com.quest.helloworld.streaming

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for media/streaming backends that render to a Surface.
 *
 * Implementations: DlnaSource (VLC/DLNA), RtspSource (VLC/RTSP), VncSource (LibVNCClient).
 */
interface StreamSource {
    val connectionState: StateFlow<ConnectionState>
    val mediaInfo: StateFlow<MediaInfo?>

    /** Start playback of the given URI. */
    fun connect(uri: String)

    /** Stop playback and release resources. */
    fun disconnect()

    /** Bind rendering output to a Surface (e.g., from a SurfaceView). */
    fun attachSurface(surface: Surface)

    /** Unbind from the current Surface. */
    fun detachSurface()

    /** Playback controls. No-op if not applicable (e.g., live streams). */
    fun play() {}
    fun pause() {}
    fun togglePlayPause() {}
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    PLAYING,
    PAUSED,
    ERROR,
}

data class MediaInfo(
    val title: String,
    val width: Int,
    val height: Int,
)
