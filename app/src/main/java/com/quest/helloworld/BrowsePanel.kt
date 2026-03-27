package com.quest.helloworld

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.quest.helloworld.streaming.DlnaDiscovery
import com.quest.helloworld.streaming.DlnaItem

/**
 * Secondary panel for browsing DLNA servers and selecting media to play.
 */
@Composable
fun BrowsePanel(
    discovery: DlnaDiscovery,
    onMediaSelected: (String) -> Unit,
) {
    val servers by discovery.servers.collectAsState()
    val isDiscovering by discovery.isDiscovering.collectAsState()

    // Navigation state: null = server list, non-null = browsing inside a server/folder
    var currentItems by remember { mutableStateOf<List<DlnaItem>?>(null) }
    var breadcrumb by remember { mutableStateOf<List<String>>(emptyList()) }

    SpatialTheme(colorScheme = draculaSpatialColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (currentItems == null) "DLNA Servers" else breadcrumb.lastOrNull() ?: "Browse",
                    style = SpatialTheme.typography.headline2Strong.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground,
                    ),
                )
                if (isDiscovering && currentItems == null) {
                    Text(
                        text = "Scanning...",
                        style = SpatialTheme.typography.body2.copy(
                            color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                        ),
                    )
                }
            }

            // Back button when browsing
            if (currentItems != null) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "< Back",
                    style = SpatialTheme.typography.body1.copy(
                        color = DraculaCyan,
                    ),
                    modifier = Modifier.clickable {
                        currentItems = null
                        breadcrumb = emptyList()
                    },
                )
            }

            Spacer(modifier = Modifier.size(16.dp))

            if (currentItems == null) {
                // Server list
                if (servers.isEmpty() && !isDiscovering) {
                    Text(
                        text = "No DLNA servers found on network",
                        style = SpatialTheme.typography.body1.copy(
                            color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                        ),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(servers) { server ->
                            BrowseListItem(
                                title = server.name,
                                isFolder = true,
                                onClick = {
                                    val items = discovery.browse(server.media)
                                    currentItems = items
                                    breadcrumb = listOf(server.name)
                                },
                            )
                        }
                    }
                }
            } else {
                // Folder/media list
                val items = currentItems!!
                if (items.isEmpty()) {
                    Text(
                        text = "Empty folder",
                        style = SpatialTheme.typography.body1.copy(
                            color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                        ),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items) { item ->
                            BrowseListItem(
                                title = item.title,
                                isFolder = item.isFolder,
                                onClick = {
                                    if (item.isFolder) {
                                        val children = discovery.browse(item.media)
                                        currentItems = children
                                        breadcrumb = breadcrumb + item.title
                                    } else {
                                        onMediaSelected(item.uri.toString())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseListItem(
    title: String,
    isFolder: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isFolder) "[+]" else "   ",
            style = SpatialTheme.typography.body1.copy(
                color = if (isFolder) DraculaYellow else DraculaGreen,
            ),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = title,
            style = SpatialTheme.typography.body1.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
