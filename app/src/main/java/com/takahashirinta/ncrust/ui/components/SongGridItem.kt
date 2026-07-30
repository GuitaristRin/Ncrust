package com.takahashirinta.ncrust.ui.components
import com.takahashirinta.ncrust.ui.theme.LocalNcrustTypography

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.anim.sokuou.SokuouPresets
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

@Composable
fun SongGridItem(song: SongItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val artistStr = song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist
    // 按压缩放动画：Animatable + graphicsLayer，动画帧只跑 draw 阶段，零 recomposition。
    // 旧实现用 animateFloatAsState + isPressed 状态，每次按下都触发列表项重组。
    val scaleAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        scope.launch { scaleAnim.animateTo(1.05f, SokuouPresets.QuickInteraction) }
                        tryAwaitRelease()
                        scope.launch { scaleAnim.animateTo(1f, SokuouPresets.QuickInteraction) }
                    },
                    onTap = { onClick() }
                )
            }
            .graphicsLayer {
                val s = scaleAnim.value
                scaleX = s
                scaleY = s
            }
    ) {
        AsyncImage(
            model = song.album?.picUrl,
            contentDescription = strings.coverDesc,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(8.dp))
        Text(
            song.name,
            color = Color.White,
            style = LocalNcrustTypography.current.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "$artistStr · ${song.album?.name ?: ""}",
            color = Color.Gray,
            style = LocalNcrustTypography.current.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
