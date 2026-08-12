package com.simplot.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.simplot.android.domain.model.PlayerSettings
import com.simplot.android.domain.model.SymbolSet
import com.simplot.android.domain.model.SymbolSize

/**
 * 玩家设置对话框（R4，对应桌面版 WindowCustomizeDisplay / WindowCustomizeColor）。
 *
 * G08（批次3）：符号尺寸档（Dots/Reduced/Default/Enlarged）、CheckFriendlySymbols、
 * CheckBackground（桌面签名："Use background color under labels"）开关。
 * G47：符号集四选（CWS Color Filled / CWS Color Unfilled / CWS Mono Filled / NTDS）
 * + WW2 作为附加切换保留。
 * G09：颜色列表编辑（6 键点击选色）+ Load/Save/Reset（颜色方案快照存 PlayerSettings.savedColors，
 * 随设置一起持久化；桌面 WindowCustomizeColor ListboxColors 语义）。
 * G10：控制选项——自动存档开关（桌面 WindowControlOptions CheckAutoSave）。
 * G11：错误日志入口（桌面 WindowErrorLog Listbox1 + UpdateErrorLog：只读列表 + 清空）。
 * 写回由 onSave 统一提交（SettingsRepository 持久化）。
 */
@Composable
fun SettingsDialog(
    settings: PlayerSettings,
    onDismiss: () -> kotlin.Unit,
    onSave: (PlayerSettings) -> kotlin.Unit,
    // ---- G10：控制选项（桌面 WindowControlOptions） ----
    autoSaveEnabled: Boolean = true,
    onAutoSaveChange: (Boolean) -> kotlin.Unit = {},
    // ---- G11：错误日志（桌面 WindowErrorLog） ----
    errorLog: List<String> = emptyList(),
    onClearErrorLog: () -> kotlin.Unit = {}
) {
    var name by remember { mutableStateOf(settings.playerName) }
    var s by remember { mutableStateOf(settings) }
    // G09：当前正在选色的颜色键索引（null=无选色弹窗）
    var pickingColorIndex by remember { mutableStateOf<Int?>(null) }
    // G09：Load/Save/Reset 操作反馈（弹窗内文字提示，替代 toast）
    var colorMsg by remember { mutableStateOf<String?>(null) }
    // G11：错误日志弹窗开关
    var showErrorLog by remember { mutableStateOf(false) }

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
                // ---- G10：控制选项（桌面 WindowControlOptions：CheckAutoSave） ----
                Text("控制选项（桌面 WindowControlOptions）", style = MaterialTheme.typography.labelMedium)
                SettingsCheckRow("自动存档（CheckAutoSave）", autoSaveEnabled) { onAutoSaveChange(!autoSaveEnabled) }
                // ---- G11：错误日志入口（桌面 WindowErrorLog） ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "错误日志 ${errorLog.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showErrorLog = true }) { Text("查看…") }
                }
                HorizontalDivider()
                // ---- G47：符号集四选 + WW2 附加切换 ----
                Text("符号集（桌面 PopupSet）", style = MaterialTheme.typography.labelMedium)
                SettingsDropdown(
                    label = "符号集",
                    options = SymbolSet.entries.map { it.label to it.label },
                    selected = s.symbolSet.label,
                    onSelect = { label -> s = s.copy(symbolSet = SymbolSet.fromLabel(label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsCheckRow("WW2 符号（附加切换）", s.ww2Symbols) { s = s.copy(ww2Symbols = !s.ww2Symbols) }
                // ---- G08：符号尺寸档 ----
                Text("符号尺寸（桌面 PopupSize）", style = MaterialTheme.typography.labelMedium)
                SettingsDropdown(
                    label = "符号尺寸",
                    options = SymbolSize.entries.map { it.label to it.label },
                    selected = s.symbolSize.label,
                    onSelect = { label -> s = s.copy(symbolSize = SymbolSize.fromLabel(label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsCheckRow("友军符号（CheckFriendlySymbols）", s.showFriendlySymbols) { s = s.copy(showFriendlySymbols = !s.showFriendlySymbols) }
                SettingsCheckRow("标签背景色（CheckBackground）", s.useLabelBackground) { s = s.copy(useLabelBackground = !s.useLabelBackground) }
                HorizontalDivider()
                SettingsCheckRow("显示网格", s.showGrid) { s = s.copy(showGrid = !s.showGrid) }
                SettingsCheckRow("显示比例尺", s.showScaleBar) { s = s.copy(showScaleBar = !s.showScaleBar) }
                SettingsCheckRow("显示标签", s.showLabels) { s = s.copy(showLabels = !s.showLabels) }
                SettingsCheckRow("显示速度领导线", s.showSpeedLeaders) { s = s.copy(showSpeedLeaders = !s.showSpeedLeaders) }
                SettingsCheckRow("显示传感器弧", s.showSensors) { s = s.copy(showSensors = !s.showSensors) }
                SettingsCheckRow("显示武器弧", s.showWeapons) { s = s.copy(showWeapons = !s.showWeapons) }
                SettingsCheckRow("显示声呐线", s.showSonar) { s = s.copy(showSonar = !s.showSonar) }
                SettingsCheckRow("显示ES线", s.showEs) { s = s.copy(showEs = !s.showEs) }
                SettingsCheckRow("显示航路点", s.showWaypoints) { s = s.copy(showWaypoints = !s.showWaypoints) }
                SettingsCheckRow("显示队形", s.showFormations) { s = s.copy(showFormations = !s.showFormations) }
                SettingsCheckRow("显示城市", s.showCities) { s = s.copy(showCities = !s.showCities) }
                SettingsCheckRow("显示国家", s.showCountries) { s = s.copy(showCountries = !s.showCountries) }
                SettingsCheckRow("显示水域", s.showWaters) { s = s.copy(showWaters = !s.showWaters) }
                SettingsCheckRow("显示深度区", s.showDepths) { s = s.copy(showDepths = !s.showDepths) }
                SettingsCheckRow("显示深度键", s.showDepthKey) { s = s.copy(showDepthKey = !s.showDepthKey) }
                HorizontalDivider()
                // ---- G09：颜色列表编辑 + Load/Save/Reset（桌面 WindowCustomizeColor）----
                Text("颜色（桌面 WindowCustomizeColor）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val snap = s.savedColors
                        if (snap != null) {
                            s = PlayerSettings.withColors(s, snap)
                            colorMsg = "已加载颜色方案"
                        } else {
                            colorMsg = "无已保存的颜色方案"
                        }
                    }) { Text("Load") }
                    TextButton(onClick = {
                        s = s.copy(savedColors = PlayerSettings.colorsOf(s))
                        colorMsg = "颜色方案已保存"
                    }) { Text("Save") }
                    TextButton(onClick = {
                        s = PlayerSettings.withColors(s, PlayerSettings.colorsOf(PlayerSettings.DEFAULT))
                        s = s.copy(savedColors = null)
                        colorMsg = "颜色已重置为默认"
                    }) { Text("Reset") }
                }
                colorMsg?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                PlayerSettings.COLOR_KEYS.forEachIndexed { i, key ->
                    ColorEditRow(label = key.label, current = key.get(s)) { pickingColorIndex = i }
                }
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

    // G09：点击选色弹窗（预设色网格；选后立即写回本地编辑态，保存按钮统一提交）
    pickingColorIndex?.let { idx ->
        val key = PlayerSettings.COLOR_KEYS[idx]
        ColorPickerDialog(
            title = "选择${key.label}颜色",
            current = key.get(s),
            onPick = { c -> s = key.set(s, c) },
            onDismiss = { pickingColorIndex = null }
        )
    }

    // G11：错误日志弹窗（桌面 WindowErrorLog：只读列表 + 清空；列表为进入时的快照，清空后同步关闭）
    if (showErrorLog) {
        ErrorLogDialog(
            log = errorLog,
            onClear = {
                onClearErrorLog()
                showErrorLog = false
            },
            onDismiss = { showErrorLog = false }
        )
    }
}

/** G11：错误日志弹窗（桌面 WindowErrorLog Listbox1：只读滚动列表 + 清空） */
@Composable
private fun ErrorLogDialog(
    log: List<String>,
    onClear: () -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("错误日志") },
        text = {
            if (log.isEmpty()) {
                Text("暂无日志", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    log.forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear, enabled = log.isNotEmpty()) { Text("清空", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 预设色板（G09 选色器）：桌面默认 6 色 + 常用色 + 各键变体 */
private val COLOR_PRESETS: List<Long> = listOf(
    0xFFF0F2F5, 0xFF1A1A2E, 0xFF102030, 0xFFE8E0D0,
    0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFF404040,
    0xFF005AC8, 0xFF1E5AA8, 0xFF2040A0, 0xFF006080,
    0xFFC81E1E, 0xFFA02020, 0xFFB03030, 0xFF801010,
    0xFF96AA82, 0xFF6B8F5A, 0xFFC8DCE8, 0xFFA8C8D8,
    0x883C789C, 0x44444444, 0x88FFFFFF, 0x88555555,
    0xFF40C040, 0xFFFFA000, 0xFFA040C0, 0xFFC06020
)

/** 颜色编辑行（G09）：标签 + 当前色块 + 点击打开选色器 */
@Composable
private fun ColorEditRow(label: String, current: Long, onClick: () -> kotlin.Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(26.dp)
                .border(1.dp, Color(0xFF666666), CircleShape)
                .background(Color(current), CircleShape)
                .clickable { onClick() }
        )
        TextButton(onClick = onClick) { Text("选色") }
    }
}

/** G09：选色弹窗（预设色网格，当前色高亮边框；点选即回调并关闭） */
@Composable
private fun ColorPickerDialog(
    title: String,
    current: Long,
    onPick: (Long) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                COLOR_PRESETS.chunked(4).forEach { rowColors ->
                    Row {
                        rowColors.forEach { c ->
                            val selected = c == current
                            Box(
                                Modifier
                                    .padding(4.dp)
                                    .size(38.dp)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0xFF999999), CircleShape)
                                    .background(Color(c), CircleShape)
                                    .clickable { onPick(c) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsCheckRow(label: String, checked: Boolean, onChange: () -> kotlin.Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onChange() })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 只读下拉（符号集/符号尺寸选择；material3 1.3.0 ExposedDropdownMenuBox API） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> kotlin.Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.second == selected }?.first ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
