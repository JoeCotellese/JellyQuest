package com.quest.helloworld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme

/**
 * Spatial panel for choosing curated theater experiences with seat positions.
 * Vertical card stack — each card has theater name, description, and inline seat selector.
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
                Text(
                    text = "X",
                    style = SpatialTheme.typography.body1.copy(
                        color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                THEATER_EXPERIENCES.forEachIndexed { theaterIdx, theater ->
                    val isActive = theaterIdx == activeTheaterIndex
                    val activeSeat = activeSeatIndices[theaterIdx]

                    TheaterCard(
                        theater = theater,
                        isActive = isActive,
                        activeSeatIndex = activeSeat,
                        onSeatSelected = { seatIdx ->
                            activeTheaterIndex = theaterIdx
                            activeSeatIndices = activeSeatIndices.toMutableList().apply {
                                this[theaterIdx] = seatIdx
                            }
                            val seat = theater.seats[seatIdx]
                            onTheaterSelected(theater.screenSizeIndex, seat.distanceIndex, theater.screenHeightM)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TheaterCard(
    theater: TheaterExperience,
    isActive: Boolean,
    activeSeatIndex: Int,
    onSeatSelected: (Int) -> Unit,
) {
    val borderColor = if (isActive) DraculaCyan else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
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
            }
        }

        Spacer(modifier = Modifier.size(6.dp))

        // Seat selector: inline segmented control
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            theater.seats.forEachIndexed { seatIdx, seat ->
                val isSelected = isActive && seatIdx == activeSeatIndex

                Text(
                    text = seat.label,
                    style = SpatialTheme.typography.body2.copy(
                        color = if (isSelected) Color.Black else
                            SpatialTheme.colorScheme.secondaryAlphaBackground,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) DraculaGreen else Color.Transparent
                        )
                        .clickable { onSeatSelected(seatIdx) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
