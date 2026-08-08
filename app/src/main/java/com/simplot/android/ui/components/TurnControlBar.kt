package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
 * 回合控制栏：Do / Undo / Next + 回合时间显示 + 回合时长自定义（XX分XX秒）
 *
 * 需求三：回合时长可自由填写 XX分XX秒，默认 3:00
 */
@Composable
fun TurnControlBar(
    file: ScenarioFile,
    onDo: () -> Unit,
    onUndo: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    tick: Int = 0
) {
    // 回合时长编辑状态（分钟/秒）
    var minutesText by remember { mutableStateOf(file.time.currentTurnInterval.minutes.toString()) }
    var secondsText by remember { mutableStateOf(file.time.currentTurnInterval.seconds.toString()) }

    @Suppress("UNUSED_EXPRESSION") tick
    val state = TurnState.detect(file)
    val interval = file.time.currentTurnInterval

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 时间信息行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("回合时间：${file.time.currentTurnTime}", style = MaterialTheme.typography.bodySmall)
                    Text("位置时间：${file.time.currentPositionTime}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${TurnState.label(state)} · 时长 ${interval.display()}",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // 回合时长自定义（需求三：XX分XX秒）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("回合时长", style = MaterialTheme.typography.bodySmall)
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
                }) { Text("设置") }
            }

            // Do / Undo / Next 按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onDo, enabled = TurnState.canDo(state), modifier = Modifier.weight(1f)) { Text("Do 移动") }
                Button(onClick = onUndo, enabled = TurnState.canUndo(state), modifier = Modifier.weight(1f)) { Text("Undo") }
                Button(onClick = onNext, enabled = TurnState.canNext(state), modifier = Modifier.weight(1f)) { Text("Next 确认") }
            }
        }
    }
}
