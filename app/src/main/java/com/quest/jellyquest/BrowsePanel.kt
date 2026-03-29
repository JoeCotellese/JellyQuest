package com.quest.jellyquest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.quest.jellyquest.streaming.AuthState
import com.quest.jellyquest.streaming.JellyfinClient
import com.quest.jellyquest.streaming.JellyfinItem
import com.quest.jellyquest.streaming.PlaybackReporter
import kotlinx.coroutines.launch
import java.util.UUID

enum class BrowseTab { BROWSE, THEATER }

/**
 * Tabbed panel combining media browsing and theater configuration.
 * Uses Quick Connect for authentication — no typing passwords in VR.
 */
@Composable
fun BrowsePanel(
    jellyfinClient: JellyfinClient,
    onMediaSelected: (JellyfinItem) -> Unit,
    currentScreen: ScreenConfig,
    onTheaterSelected: (theater: TheaterExperience, seat: SeatPosition) -> Unit,
    spatialAudioEnabled: Boolean = true,
    onSpatialAudioToggled: (Boolean) -> Unit = {},
    roomAcousticsEnabled: Boolean = true,
    onRoomAcousticsToggled: (Boolean) -> Unit = {},
) {
    val authState by jellyfinClient.authState.collectAsState()
    val errorMessage by jellyfinClient.errorMessage.collectAsState()
    // Scope at this level survives child composable transitions (prompt -> waiting -> browser)
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(BrowseTab.BROWSE) }

    SpatialTheme(colorScheme = draculaSpatialColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(24.dp),
        ) {
            // Tab bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (activeTab == BrowseTab.BROWSE) {
                        PrimaryButton(label = "Browse", expanded = true, onClick = {})
                    } else {
                        SecondaryButton(label = "Browse", expanded = true, onClick = { activeTab = BrowseTab.BROWSE })
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (activeTab == BrowseTab.THEATER) {
                        PrimaryButton(label = "Theater", expanded = true, onClick = {})
                    } else {
                        SecondaryButton(label = "Theater", expanded = true, onClick = { activeTab = BrowseTab.THEATER })
                    }
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            // Tab content
            when (activeTab) {
                BrowseTab.BROWSE -> {
                    when (authState) {
                        AuthState.DISCONNECTED, AuthState.ERROR -> {
                            QuickConnectPrompt(
                                onConnect = {
                                    scope.launch { jellyfinClient.startQuickConnect() }
                                },
                                errorMessage = if (authState == AuthState.ERROR) errorMessage else null,
                            )
                        }
                        AuthState.QUICK_CONNECT_PENDING -> {
                            QuickConnectWaiting(jellyfinClient = jellyfinClient)
                        }
                        AuthState.AUTHENTICATED -> {
                            LibraryBrowser(
                                jellyfinClient = jellyfinClient,
                                onMediaSelected = onMediaSelected,
                            )
                        }
                    }
                }
                BrowseTab.THEATER -> {
                    TheaterPickerContent(
                        currentScreen = currentScreen,
                        onTheaterSelected = onTheaterSelected,
                        spatialAudioEnabled = spatialAudioEnabled,
                        onSpatialAudioToggled = onSpatialAudioToggled,
                        roomAcousticsEnabled = roomAcousticsEnabled,
                        onRoomAcousticsToggled = onRoomAcousticsToggled,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickConnectPrompt(
    onConnect: () -> Unit,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "JellyQuest",
            style = SpatialTheme.typography.headline1Strong.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )

        Spacer(modifier = Modifier.size(8.dp))

        Text(
            text = JellyfinClient.DEFAULT_SERVER_URL,
            style = SpatialTheme.typography.body2.copy(
                color = SpatialTheme.colorScheme.secondaryAlphaBackground,
            ),
        )

        Spacer(modifier = Modifier.size(24.dp))

        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = DraculaGreen,
                contentColor = Color.Black,
            ),
        ) {
            Text("Connect with Quick Connect")
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = errorMessage,
                style = SpatialTheme.typography.body2.copy(
                    color = Color(0xFFFF5555),
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QuickConnectWaiting(jellyfinClient: JellyfinClient) {
    val code by jellyfinClient.quickConnectCode.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Quick Connect",
            style = SpatialTheme.typography.headline2Strong.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )

        Spacer(modifier = Modifier.size(16.dp))

        if (code != null) {
            Text(
                text = "Enter this code in your Jellyfin dashboard:",
                style = SpatialTheme.typography.body1.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.size(16.dp))

            // Large code display
            Text(
                text = code!!,
                style = SpatialTheme.typography.headline1Strong.copy(
                    color = DraculaGreen,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )

            Spacer(modifier = Modifier.size(24.dp))

            Text(
                text = "Waiting for authorization...",
                style = SpatialTheme.typography.body2.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
            )
        } else {
            Text(
                text = "Connecting to server...",
                style = SpatialTheme.typography.body1.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
            )
        }

        Spacer(modifier = Modifier.size(16.dp))

        Text(
            text = "Cancel",
            style = SpatialTheme.typography.body2.copy(color = Color(0xFFFF5555)),
            modifier = Modifier.clickable { jellyfinClient.disconnect() },
        )
    }
}

@Composable
private fun LibraryBrowser(
    jellyfinClient: JellyfinClient,
    onMediaSelected: (JellyfinItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentItems by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var breadcrumb by remember { mutableStateOf<List<Pair<String, UUID?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Use pre-fetched data if available, otherwise fetch on demand
    LaunchedEffect(Unit) {
        val cached = jellyfinClient.cachedLibraries.value
        if (cached != null) {
            currentItems = cached
        } else {
            isLoading = true
            currentItems = jellyfinClient.getLibraries()
            isLoading = false
        }
    }

    // Header
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = breadcrumb.lastOrNull()?.first ?: "Libraries",
            style = SpatialTheme.typography.headline2Strong.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )
        Text(
            text = "Disconnect",
            style = SpatialTheme.typography.body2.copy(color = Color(0xFFFF5555)),
            modifier = Modifier.clickable { jellyfinClient.disconnect() },
        )
    }

    // Back button when navigating
    if (breadcrumb.isNotEmpty()) {
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "< Back",
            style = SpatialTheme.typography.body1.copy(color = DraculaCyan),
            modifier = Modifier.clickable {
                val newBreadcrumb = breadcrumb.dropLast(1)
                val parentId = newBreadcrumb.lastOrNull()?.second
                // Check cache before fetching
                val cached = if (parentId != null) {
                    jellyfinClient.cachedItems.value[parentId]
                } else {
                    jellyfinClient.cachedLibraries.value
                }
                if (cached != null) {
                    breadcrumb = newBreadcrumb
                    currentItems = cached
                } else {
                    isLoading = true
                    scope.launch {
                        breadcrumb = newBreadcrumb
                        currentItems = if (parentId != null) {
                            jellyfinClient.getItems(parentId)
                        } else {
                            jellyfinClient.getLibraries()
                        }
                        isLoading = false
                    }
                }
            },
        )
    }

    Spacer(modifier = Modifier.size(16.dp))

    if (isLoading) {
        Text(
            text = "Loading...",
            style = SpatialTheme.typography.body1.copy(
                color = SpatialTheme.colorScheme.secondaryAlphaBackground,
            ),
        )
    } else {
        val items = currentItems ?: emptyList()
        if (items.isEmpty()) {
            Text(
                text = "No items found",
                style = SpatialTheme.typography.body1.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items) { item ->
                    BrowseListItem(
                        item = item,
                        onClick = {
                            if (item.isFolder) {
                                val cachedChildren = jellyfinClient.cachedItems.value[item.id]
                                if (cachedChildren != null) {
                                    currentItems = cachedChildren
                                    breadcrumb = breadcrumb + (item.name to item.id)
                                } else {
                                    isLoading = true
                                    scope.launch {
                                        val children = jellyfinClient.getItems(item.id)
                                        currentItems = children
                                        breadcrumb = breadcrumb + (item.name to item.id)
                                        isLoading = false
                                    }
                                }
                            } else {
                                onMediaSelected(item)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseListItem(
    item: JellyfinItem,
    onClick: () -> Unit,
) {
    val hasPosition = !item.isFolder && item.playbackPositionTicks > 0
    val hasProgress = hasPosition && item.runTimeTicks > 0
    val fullyWatched = hasProgress && PlaybackReporter.isFullyWatched(
        item.playbackPositionTicks, item.runTimeTicks,
    )
    val progressPercent = if (hasProgress) {
        PlaybackReporter.computeProgressPercent(item.playbackPositionTicks, item.runTimeTicks)
    } else 0
    val remainingMin = if (hasProgress && !fullyWatched) {
        PlaybackReporter.computeRemainingMinutes(item.playbackPositionTicks, item.runTimeTicks)
    } else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (item.isFolder) "[+]" else "   ",
                style = SpatialTheme.typography.body1.copy(
                    color = if (item.isFolder) DraculaYellow else DraculaGreen,
                ),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = item.name,
                style = SpatialTheme.typography.body1.copy(
                    color = SpatialTheme.colorScheme.primaryAlphaBackground,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fullyWatched) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Watched",
                    style = SpatialTheme.typography.body2.copy(color = DraculaGreen),
                )
            } else if (hasProgress && remainingMin > 0) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "${remainingMin} min left",
                    style = SpatialTheme.typography.body2.copy(color = DraculaOrange),
                )
            } else if (hasPosition && !hasProgress) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "In Progress",
                    style = SpatialTheme.typography.body2.copy(color = DraculaOrange),
                )
            }
        }
        if (hasProgress && !fullyWatched && progressPercent > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .background(DraculaCurrentLine)
                    .height(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressPercent / 100f)
                        .background(DraculaPurple)
                        .height(2.dp),
                )
            }
        }
    }
}
