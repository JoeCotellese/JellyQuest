package com.quest.jellyquest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.SpatialTheme
import com.quest.jellyquest.streaming.ConnectionState
import com.quest.jellyquest.streaming.StreamSource

/**
 * Overlay panel composable layered on top of the VideoSurfacePanelRegistration screen.
 * Renders status text (paused, connecting, error) over the video, and falls back to
 * HelloPanel when disconnected. Transparent when video is actively playing.
 */
@Composable
fun MonitorPanel(
    streamSource: StreamSource?,
    screenConfig: State<ScreenConfig>,
) {
    if (streamSource == null) {
        HelloPanel(screenConfig = screenConfig)
        return
    }

    val state by streamSource.connectionState.collectAsState()
    val info by streamSource.mediaInfo.collectAsState()

    when (state) {
        ConnectionState.DISCONNECTED -> {
            HelloPanel(screenConfig = screenConfig)
        }
        ConnectionState.CONNECTING -> {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Connecting...",
                    style = SpatialTheme.typography.body1,
                    color = Color.White,
                )
            }
        }
        ConnectionState.PLAYING -> {
            // Transparent — video from the screen panel shows through
        }
        ConnectionState.PAUSED -> {
            Box(modifier = Modifier.fillMaxSize()) {
                // Semi-transparent overlay so paused video is still visible beneath
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Paused",
                        style = SpatialTheme.typography.headline1Strong,
                        color = Color.White,
                    )
                }

                // Media title and resolution at bottom
                info?.let { mediaInfo ->
                    Text(
                        text = "${mediaInfo.title} (${mediaInfo.width}x${mediaInfo.height})",
                        style = SpatialTheme.typography.body2,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    )
                }
            }
        }
        ConnectionState.ERROR -> {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Connection Error",
                    style = SpatialTheme.typography.body1,
                    color = Color(0xFFFF5555),
                )
            }
        }
    }
}
