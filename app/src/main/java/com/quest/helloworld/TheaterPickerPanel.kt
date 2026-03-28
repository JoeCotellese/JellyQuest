package com.quest.helloworld

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.BorderlessCircleButton
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.control.SpatialRadioButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.meta.spatial.uiset.theme.icons.SpatialIcons
import com.meta.spatial.uiset.theme.icons.regular.Close

/**
 * Spatial panel for choosing curated theater experiences with seat positions.
 * Uses UISet radio buttons for theater selection and buttons for seat picking.
 */
@Composable
fun TheaterPickerPanel(
    currentSizeIndex: Int,
    currentDistanceIndex: Int,
    onTheaterSelected: (sizeIndex: Int, distanceIndex: Int, screenHeightM: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // Find which theater and seat are currently active
    var activeTheaterIndex by remember {
        mutableIntStateOf(
            THEATER_EXPERIENCES.indexOfFirst { it.screenSizeIndex == currentSizeIndex }
                .coerceAtLeast(0)
        )
    }
    var activeSeatIndices by remember {
        mutableStateOf(
            THEATER_EXPERIENCES.map { theater ->
                theater.seats.indexOfFirst { it.distanceIndex == currentDistanceIndex }
                    .let { if (it >= 0) it else 1 }
            }
        )
    }

    SpatialTheme(colorScheme = draculaSpatialColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Theater",
                    style = SpatialTheme.typography.headline3Strong.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground,
                    ),
                )
                BorderlessCircleButton(
                    icon = { Icon(SpatialIcons.Regular.Close, "Close", modifier = Modifier.size(16.dp)) },
                    onClick = { onDismiss() },
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            // Theater list
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                THEATER_EXPERIENCES.forEachIndexed { theaterIdx, theater ->
                    val isActive = theaterIdx == activeTheaterIndex

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SpatialRadioButton(
                            selected = isActive,
                            onClick = {
                                activeTheaterIndex = theaterIdx
                                val seat = theater.seats[activeSeatIndices[theaterIdx]]
                                onTheaterSelected(theater.screenSizeIndex, seat.distanceIndex, theater.screenHeightM)
                            },
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = theater.name,
                                style = SpatialTheme.typography.body1.copy(
                                    color = SpatialTheme.colorScheme.primaryAlphaBackground,
                                ),
                            )
                            Text(
                                text = theater.description,
                                style = SpatialTheme.typography.body2.copy(
                                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                                ),
                            )

                            Spacer(modifier = Modifier.size(6.dp))

                            // Seat selector
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                theater.seats.forEachIndexed { seatIdx, seat ->
                                    val isSelected = isActive && seatIdx == activeSeatIndices[theaterIdx]
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (isSelected) {
                                            PrimaryButton(
                                                label = seat.label,
                                                expanded = true,
                                                onClick = { /* already selected */ },
                                            )
                                        } else {
                                            SecondaryButton(
                                                label = seat.label,
                                                expanded = true,
                                                onClick = {
                                                    activeTheaterIndex = theaterIdx
                                                    activeSeatIndices = activeSeatIndices.toMutableList().apply {
                                                        this[theaterIdx] = seatIdx
                                                    }
                                                    onTheaterSelected(theater.screenSizeIndex, seat.distanceIndex, theater.screenHeightM)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
