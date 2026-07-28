package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistSearchItem(
    artist: ArtistSearchItem,
    onClick: () -> Unit,
    menuContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val strings = LocalStrings.current
    val aliasStr = artist.alias?.joinToString(" / ") ?: ""
    val transStr = artist.trans ?: ""

    var showMenu by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (menuContent != null) showMenu = true }
            )
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(72.dp)) {
            AsyncImage(
                model = artist.picUrl,
                contentDescription = strings.artistAvatarDesc,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2A2A)),
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
                strings.artistStats(artist.albumSize ?: 0, artist.musicSize ?: 0),
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (menuContent != null) {
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RectangleShape,
            containerColor = Color(0xFF282828)
        ) {
            menuContent()
        }
    }
    }
}
