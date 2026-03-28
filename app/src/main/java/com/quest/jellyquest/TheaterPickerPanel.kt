package com.quest.jellyquest

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.control.SpatialRadioButton
import com.meta.spatial.uiset.theme.SpatialTheme

/**
 * Theater experience and seat picker content, designed to be embedded
 * inside a tabbed panel. No outer wrapper/background — the host provides that.
 */
@Composable
fun TheaterPickerContent(
    currentScreen: ScreenConfig,
    onTheaterSelected: (theater: TheaterExperience, seat: SeatPosition) -> Unit,
) {
    // Find which theater and seat are currently active based on screen dimensions
    var activeTheaterIndex by remember {
        mutableIntStateOf(
            THEATER_EXPERIENCES.indexOfFirst {
                it.screenWidthM == currentScreen.widthM && it.screenHeightM == currentScreen.heightM
            }.coerceAtLeast(0)
        )
    }
    var activeSeatIndices by remember {
        mutableStateOf(
            THEATER_EXPERIENCES.map { theater ->
                theater.seats.indexOfFirst { it.distanceM == currentScreen.distanceM }
                    .let { if (it >= 0) it else 1 }
            }
        )
    }

    // Header
    Text(
        text = "Theater",
        style = SpatialTheme.typography.headline3Strong.copy(
            color = SpatialTheme.colorScheme.primaryAlphaBackground,
        ),
    )

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
                        onTheaterSelected(theater, seat)
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
                                            onTheaterSelected(theater, seat)
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
