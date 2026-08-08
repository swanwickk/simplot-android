package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplot.android.engine.ReplayEngine

/**
 * 回放控制条（对应桌面版 Turn Replay 窗口）：
 * Play（自动播放）/ Pause / Back（上一帧）/ Forward（下一帧）+ 时间滑块。
 */
@Composable
fun ReplayBar(
    timeline: List<ReplayEngine.Frame>,
    frameIndex: Int,
    playing: Boolean,
    onFrameChange: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onSpeedChange: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (timeline.isEmpty()) return
    // 倍速（桌面版 PopupSpeed：1x/2x/4x/8x）
    val speeds = listOf(1000L, 500L, 250L, 125L)
    val speedLabels = listOf("1x", "2x", "4x", "8x")
    var speedIdx by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "回放：${timeline[frameIndex].time}（${frameIndex + 1}/${timeline.size}）",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = frameIndex.toFloat(),
                onValueChange = { onFrameChange(it.toInt().coerceIn(0, timeline.size - 1)) },
                valueRange = 0f..(timeline.size - 1).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { onFrameChange((frameIndex - 1).coerceAtLeast(0)) }, modifier = Modifier.weight(1f)) {
                    Text("◀ 上帧")
                }
                Button(onClick = onPlayPause, modifier = Modifier.weight(1f)) {
                    Text(if (playing) "⏸ 暂停" else "▶ 播放")
                }
                Button(onClick = { onFrameChange((frameIndex + 1).coerceAtMost(timeline.size - 1)) }, modifier = Modifier.weight(1f)) {
                    Text("下帧 ▶")
                }
                Box(Modifier.weight(1f)) {
                    Button(onClick = { menuOpen = true }) {
                        Text("${speedLabels[speedIdx]} ⏩")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        speedLabels.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    speedIdx = i
                                    menuOpen = false
                                    onSpeedChange(speeds[i])
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
