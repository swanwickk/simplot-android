package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplot.android.ui.isValidScenarioStartTime

/**
 * 新场景创建对话框（G01，对应桌面版 WindowNewScenario）。
 *
 * 桌面版字段：TextName（场景名）+ TextYear/Month/Day/Hour/Minute/Second（起始日期时间）
 * + PushMap（选地图）+ PushOk/PushCancel。
 *
 * 触屏化调整：
 * - 起始日期时间合并为两个输入框（日期 YYYY-MM-DD + 时间 HH:MM:SS），创建时拼接校验
 *   （严格格式 YYYY-MM-DD HH:MM:SS，与存档 Time 字段一致，复用 TimeUtil 解析校验）
 * - 地图选择：三态 = 未选（TypeOfMap=0）/ 已选文件（显示文件名，TypeOfMap=1）/ 清除。
 *   文件选择走 SAF（MainActivity 回调 onPickMap），选中即加载到画布预览并把文件名
 *   回填到 [mapFileName]（创建时写入 Scenario.MapFileName，保存后重开按桌面语义自动加载）。
 *
 * @param defaultStartTime 默认起始时间（YYYY-MM-DD HH:MM:SS，ViewModel 提供当前时刻）
 * @param mapFileName      当前已选地图文件名（null = 未选/无地图）
 * @param onPickMap        请求启动系统文件选择（MainActivity SAF）
 * @param onClearMap       清除已选地图
 * @param onCreate         创建回调（name, startTime, mapFileName；mapFileName=null 表示无地图）
 */
@Composable
fun NewScenarioDialog(
    defaultStartTime: String,
    mapFileName: String?,
    onDismiss: () -> Unit,
    onPickMap: () -> Unit,
    onClearMap: () -> Unit,
    onCreate: (name: String, startTime: String, mapFileName: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    // 默认起始时间拆分为日期 + 时间两个输入框（容忍缺秒/无空格分隔的默认值）
    var date by remember { mutableStateOf(defaultStartTime.substringBefore(' ').ifBlank { "2026-01-01" }) }
    var time by remember { mutableStateOf(defaultStartTime.substringAfter(' ', "").ifBlank { "00:00:00" }) }

    val startTime = "${date.trim()} ${time.trim()}"
    val startTimeValid = isValidScenarioStartTime(startTime)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新场景") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // ---- 1. 场景名（桌面 TextName） ----
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("场景名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ---- 2. 起始日期时间（桌面 TextYear/Month/Day/Hour/Minute/Second） ----
                Text("起始日期时间", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = date, onValueChange = { date = it },
                        label = { Text("日期 YYYY-MM-DD") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = time, onValueChange = { time = it },
                        label = { Text("时间 HH:MM:SS") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!startTimeValid) {
                    Text(
                        "格式应为 YYYY-MM-DD HH:MM:SS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // ---- 3. 地图选择（桌面 PushMap；触屏走 SAF 文件选择） ----
                Text("地图", style = MaterialTheme.typography.titleSmall)
                if (mapFileName.isNullOrBlank()) {
                    Text("未选择地图（TypeOfMap=0）", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "已选：$mapFileName（TypeOfMap=1）",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickMap, modifier = Modifier.weight(1f)) { Text("选择地图") }
                    if (!mapFileName.isNullOrBlank()) {
                        OutlinedButton(onClick = onClearMap, modifier = Modifier.weight(1f)) { Text("清除") }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), startTime, mapFileName?.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank() && startTimeValid
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
