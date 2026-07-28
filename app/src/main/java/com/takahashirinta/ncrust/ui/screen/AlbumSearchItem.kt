package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumSearchItem(
    album: AlbumSearchItem,
    onClick: () -> Unit,
    menuContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val strings = LocalStrings.current
    val publishYear = album.publishTime?.let {
        java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(it))
    } ?: ""

    var showMenu by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (menuContent != null) showMenu = true }
            )
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.picUrl,
            contentDescription = strings.albumCoverDesc,
            modifier = Modifier.size(72.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                album.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${album.artist?.name ?: strings.unknownArtist}${
                    if (publishYear.isNotEmpty()) " · $publishYear" else ""
                }${album.company?.let { " · $it" } ?: ""}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        album.size?.let {
            Text(
                strings.trackCount(it),
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
