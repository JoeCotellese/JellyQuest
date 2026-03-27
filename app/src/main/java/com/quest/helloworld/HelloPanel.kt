package com.quest.helloworld

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme

@Composable
fun HelloPanel(
    sizeIndex: State<Int>,
    distanceIndex: State<Int>,
) {
  val size = SCREEN_SIZES[sizeIndex.value]
  val distance = DISTANCES[distanceIndex.value]

  SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      Text(
          text = size.label,
          textAlign = TextAlign.Center,
          style =
              SpatialTheme.typography.headline1Strong.copy(
                  color = SpatialTheme.colorScheme.primaryAlphaBackground
              ),
      )
      Spacer(modifier = Modifier.size(16.dp))
      Text(
          text = "${distance.label} — ${distance.distanceM}m away",
          textAlign = TextAlign.Center,
          style =
              SpatialTheme.typography.body1.copy(
                  color = SpatialTheme.colorScheme.secondaryAlphaBackground
              ),
      )
      Spacer(modifier = Modifier.size(32.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
            text = "\u25C0 Size \u25B6",
            style =
                SpatialTheme.typography.body2.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground
                ),
        )
        Text(
            text = "\u25B2 Distance \u25BC",
            style =
                SpatialTheme.typography.body2.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground
                ),
        )
      }
    }
  }
}

@Composable
private fun getPanelTheme(): SpatialColorScheme = draculaSpatialColorScheme()
