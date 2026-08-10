package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplot.android.domain.model.PlayerSettings

/**
 * 玩家设置对话框（R4，对应桌面版 WindowCustomizeDisplay）。
 *
 * 显示开关：网格/比例尺/标签/速度领导线/传感器/武器/航路点/队形/城市/国家/水域/深度。
 * 玩家名编辑。写回由 onSave 统一提交（SettingsRepository 持久化）。
 */
@Composable
fun SettingsDialog(
    settings: PlayerSettings,
    onDismiss: () -> kotlin.Unit,
    onSave: (PlayerSettings) -> kotlin.Unit
) {
    var name by remember { mutableStateOf(settings.playerName) }
    var s by remember { mutableStateOf(settings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("玩家名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                SettingsCheckRow("显示网格", s.showGrid) { s = s.copy(showGrid = !s.showGrid) }
                SettingsCheckRow("显示比例尺", s.showScaleBar) { s = s.copy(showScaleBar = !s.showScaleBar) }
                SettingsCheckRow("显示标签", s.showLabels) { s = s.copy(showLabels = !s.showLabels) }
                SettingsCheckRow("显示速度领导线", s.showSpeedLeaders) { s = s.copy(showSpeedLeaders = !s.showSpeedLeaders) }
                SettingsCheckRow("显示传感器弧", s.showSensors) { s = s.copy(showSensors = !s.showSensors) }
                SettingsCheckRow("显示武器弧", s.showWeapons) { s = s.copy(showWeapons = !s.showWeapons) }
                SettingsCheckRow("显示航路点", s.showWaypoints) { s = s.copy(showWaypoints = !s.showWaypoints) }
                SettingsCheckRow("显示队形", s.showFormations) { s = s.copy(showFormations = !s.showFormations) }
                SettingsCheckRow("显示城市", s.showCities) { s = s.copy(showCities = !s.showCities) }
                SettingsCheckRow("显示国家", s.showCountries) { s = s.copy(showCountries = !s.showCountries) }
                SettingsCheckRow("显示水域", s.showWaters) { s = s.copy(showWaters = !s.showWaters) }
                SettingsCheckRow("显示深度区", s.showDepths) { s = s.copy(showDepths = !s.showDepths) }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(s.copy(playerName = name.ifBlank { "Player" }))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsCheckRow(label: String, checked: Boolean, onChange: () -> kotlin.Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onChange() })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
