package com.quest.helloworld

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meta.spatial.uiset.theme.SpatialTheme
import com.quest.helloworld.streaming.ConnectionState
import com.quest.helloworld.streaming.StreamSource

/**
 * Main monitor composable. When a StreamSource is active and connected,
 * renders video via a SurfaceView. Otherwise falls back to HelloPanel.
 */
@Composable
fun MonitorPanel(
    streamSource: StreamSource?,
    sizeIndex: State<Int>,
    distanceIndex: State<Int>,
) {
    if (streamSource == null) {
        HelloPanel(sizeIndex = sizeIndex, distanceIndex = distanceIndex)
        return
    }

    val state by streamSource.connectionState.collectAsState()
    val info by streamSource.mediaInfo.collectAsState()

    when (state) {
        ConnectionState.DISCONNECTED -> {
            HelloPanel(sizeIndex = sizeIndex, distanceIndex = distanceIndex)
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
        ConnectionState.PLAYING,
        ConnectionState.PAUSED -> {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                // SurfaceView for VLC rendering
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    streamSource.attachSurface(holder.surface)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    streamSource.detachSurface()
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Overlay: media info when paused
                if (state == ConnectionState.PAUSED) {
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
                }

                // Media title overlay at bottom
                info?.let { mediaInfo ->
                    Text(
                        text = "${mediaInfo.title} (${mediaInfo.width}x${mediaInfo.height})",
                        style = SpatialTheme.typography.body2,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    )
                }
            }

            // Cleanup when composable leaves composition
            DisposableEffect(streamSource) {
                onDispose {
                    streamSource.detachSurface()
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
