package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

@Composable
fun PlayAllDialog(
    songCount: Int,
    onDismiss: () -> Unit,
    onReplaceAndPlay: () -> Unit,
    onInsertNext: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            tonalElevation = 0.dp
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = strings.songCountFormat(songCount),
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
                HorizontalDivider(color = Color(0xFF2A2A2A))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReplaceAndPlay(); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(strings.playNowTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(strings.playNowDesc, color = Color.Gray, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF2A2A2A))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInsertNext(); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(strings.insertNextTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(strings.insertNextDesc, color = Color.Gray, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF2A2A2A))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(strings.cancel, color = Color.Gray, fontSize = 15.sp)
                }
            }
        }
    }
}
