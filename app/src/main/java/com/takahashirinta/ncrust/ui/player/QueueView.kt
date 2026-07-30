package com.takahashirinta.ncrust.ui.player
import com.takahashirinta.ncrust.ui.components.NcrustIconButton
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.anim.sokuou.rememberMetroFlingBehavior
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

@Composable
fun QueueView(
    queue: List<SongItem>,
    currentIndex: Int,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit
) {
    val strings = LocalStrings.current
    if (queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.emptyQueue, color = Color.Gray, fontSize = 16.sp)
        }
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            flingBehavior = rememberMetroFlingBehavior()
        ) {
            // key = song.id 让 LazyColumn 在插入/删除时按身份 diff，只重建真正变化的项。
            // 缺少 key 时删首行/中间行会重组所有可见项 → 低端机上一次修改 20+ 首歌 recomposition
            itemsIndexed(queue, key = { _, s -> s.id }) { index, song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onPlayIndex(index) },
                    isCurrentPlaying = index == currentIndex,
                    actions = {
                        NcrustIconButton(onClick = { onRemoveIndex(index) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(LocalNcrustColors.current.background, Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, LocalNcrustColors.current.background)
                    )
                )
        )
    }
}
