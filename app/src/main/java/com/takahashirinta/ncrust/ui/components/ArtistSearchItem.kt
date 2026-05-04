package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.ArtistSearchItem

@Composable
fun ArtistSearchItem(artist: ArtistSearchItem, onClick: () -> Unit) {
    val aliasStr = artist.alias?.joinToString(" / ") ?: ""
    val transStr = artist.trans ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp)) {
            AsyncImage(
                model = artist.picUrl,
                contentDescription = "艺人头像",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2A2A), CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    artist.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (transStr.isNotEmpty()) {
                    Text(
                        " · $transStr",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (aliasStr.isNotEmpty()) {
                Text(
                    aliasStr,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "专辑: ${artist.albumSize ?: 0} · 单曲: ${artist.musicSize ?: 0}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
