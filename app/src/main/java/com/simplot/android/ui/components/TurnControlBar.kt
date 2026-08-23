package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.data.model.TurnInterval
import com.simplot.android.engine.TurnState

/**
 * 回合控制栏：按"时钟信息 / 回合时长 / 回合操作"三段式卡片分组（P1-1）。
 *
 * 原先"双时钟+时长输入+Do/Undo/Next"挤在一卡，长时盯屏扫描成本高。
 * 现拆为三段卡片，海图之上居家可长时间推演。
 */
@Composable
fun TurnControlBar(
    file: ScenarioFile,
    onDo: () -> Unit,
    onUndo: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    tick: Int = 0,
    vmTurnState: TurnState.State? = null,
    onIntervalSet: ((minutes: Int, seconds: Int) -> Unit)? = null
) {
    var minutesText by remember(file) { mutableStateOf(file.time.currentTurnInterval.minutes.toString()) }
    var secondsText by remember(file) { mutableStateOf(file.time.currentTurnInterval.seconds.toString()) }

    @Suppress("UNUSED_EXPRESSION") tick
    val state = vmTurnState ?: TurnState.detect(file)
    val interval = file.time.currentTurnInterval

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 段一：时钟信息（居家可一瞥获知推进进度）
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("回合时间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(file.time.currentTurnTime, style = MaterialTheme.typography.bodyMedium)
                    Text("位置时间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(file.time.currentPositionTime, style = MaterialTheme.typography.bodyMedium)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "${TurnState.label(state)} · ${interval.display()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 段二：回合时长输入（独立卡片，设置时长为次要操作）
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("回合时长", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                OutlinedTextField(
                    value = minutesText, onValueChange = { minutesText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("分") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Text(":", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = secondsText, onValueChange = { secondsText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val m = minutesText.toIntOrNull() ?: 3
                    val s = secondsText.toIntOrNull() ?: 0
                    file.time.currentTurnInterval = TurnInterval(m, s)
                    minutesText = m.toString()
                    secondsText = s.toString()
                    onIntervalSet?.invoke(m, s)
                }) { Text("设置") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text(
            "回合操作可在底部主操作栏快速触发（拇指可达）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
