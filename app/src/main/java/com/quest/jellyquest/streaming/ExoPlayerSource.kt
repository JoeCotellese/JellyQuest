@file:OptIn(UnstableApi::class)

package com.quest.jellyquest.streaming

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StreamSource backed by ExoPlayer (Media3) for HTTP/HLS/DASH video playback.
 * Uses the standard Android media stack for HTTP/HLS/DASH playback.
 */
class ExoPlayerSource(context: Context) : StreamSource {

    companion object {
        private const val TAG = "VirtualMonitor"
        private const val BUMPER_VOLUME = 0.1f
    }

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    var isBumperPlaying: Boolean = false
        private set

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    override val mediaInfo: StateFlow<MediaInfo?> = _mediaInfo.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    Player.STATE_READY -> {
                        _connectionState.value = if (player.isPlaying) {
                            ConnectionState.PLAYING
                        } else {
                            ConnectionState.PAUSED
                        }
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_READY) {
                    _connectionState.value = if (isPlaying) {
                        ConnectionState.PLAYING
                    } else {
                        ConnectionState.PAUSED
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val currentTitle = _mediaInfo.value?.title
                        ?: player.mediaMetadata.title?.toString()
                        ?: player.currentMediaItem?.localConfiguration?.uri
                            ?.lastPathSegment
                        ?: "Unknown"
                    _mediaInfo.value = MediaInfo(
                        title = currentTitle,
                        width = videoSize.width,
                        height = videoSize.height,
                    )
                    Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                _connectionState.value = ConnectionState.ERROR
            }
        })
    }

    /** Play bundled bumper videos in a loop at 50% volume until real content is selected. */
    fun playBumpers(context: Context, bumperResIds: List<Int>) {
        disconnect()
        if (bumperResIds.isEmpty()) return
        Log.i(TAG, "Playing ${bumperResIds.size} bumper(s) at 50% volume")
        val items = bumperResIds.map { resId ->
            val uri = Uri.parse("android.resource://${context.packageName}/$resId")
            MediaItem.fromUri(uri)
        }
        player.setMediaItems(items)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = BUMPER_VOLUME
        player.prepare()
        player.play()
        isBumperPlaying = true
    }

    override fun connect(uri: String) {
        connect(uri, startPositionMs = 0, startPaused = false)
    }

    /** Connect with optional resume position. If startPaused, prepares but does not auto-play. */
    fun connect(uri: String, startPositionMs: Long, startPaused: Boolean) {
        disconnect()
        Log.i(TAG, "ExoPlayer connecting to: $uri (startAt=${startPositionMs}ms, paused=$startPaused)")
        _connectionState.value = ConnectionState.CONNECTING
        player.setMediaItem(MediaItem.fromUri(uri))
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs)
        }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1.0f
        player.prepare()
        if (!startPaused) {
            player.play()
        }
    }

    override fun disconnect() {
        player.stop()
        player.clearMediaItems()
        isBumperPlaying = false
        _connectionState.value = ConnectionState.DISCONNECTED
        _mediaInfo.value = null
    }

    override fun attachSurface(surface: Surface) {
        player.setVideoSurface(surface)
    }

    override fun detachSurface() {
        player.setVideoSurface(null)
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekForward(seconds: Long) {
        player.seekTo(player.currentPosition + (seconds * 1000))
    }

    override fun seekBackward(seconds: Long) {
        player.seekTo((player.currentPosition - (seconds * 1000)).coerceAtLeast(0))
    }

    override fun stop() {
        disconnect()
    }

    /** Release the ExoPlayer instance. Call from Activity.onDestroy(). */
    fun release() {
        player.release()
    }
}
