package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.Unit

/**
 * 单位属性编辑弹层（长按单位弹出，触摸屏替代桌面右键表单）
 * - 航向/航速：数字输入（反馈⑨：去掉滑杆，改为纯输入）
 * - 高度（飞机）/深度（潜艇）
 * - 可见性：对蓝/红方可见开关（需求二）
 * - 沉没标记
 * - 反馈⑨：内容超屏可滚动（Column verticalScroll）
 */
@Composable
fun UnitEditSheet(
    unit: Unit,
    onApply: (Unit) -> kotlin.Unit,
    onDelete: (Unit) -> kotlin.Unit,
    onShowAsSunk: (Unit) -> kotlin.Unit = {},
    onCopy: (Unit) -> kotlin.Unit = {},
    onDismiss: () -> kotlin.Unit,
    // 被动方位 Emitter 候选：当前剧本全部单位（玩家从下拉选取，不再手输 IdNum）
    allUnits: List<Unit> = emptyList()
) {
    // 反馈⑨：航向/航速纯数字输入（去掉滑杆；文本与数值双向同步，非法输入保留上次有效值）
    var course by remember { mutableFloatStateOf(unit.courseDeg().toFloat()) }
    var speed by remember { mutableFloatStateOf(unit.speedKnots().toFloat()) }
    var courseText by remember { mutableStateOf(formatCourseSpeed(unit.courseDeg())) }
    var speedText by remember { mutableStateOf(formatCourseSpeed(unit.speedKnots())) }
    var alt by remember { mutableStateOf(unit.altitudeMeters()?.toString() ?: "") }
    var depth by remember { mutableStateOf(unit.depthMeters()?.toString() ?: "") }
    // R-P2：X/Y 编辑（桌面版各单位窗口有 X/Y 字段，替代无 Relocate 时的位置调整）
    var xText by remember { mutableStateOf(String.format(java.util.Locale.US, "%.3f", unit.x / 100000.0)) }
    var yText by remember { mutableStateOf(String.format(java.util.Locale.US, "%.3f", unit.y / 100000.0)) }
    // G13：身份/类型/阵营/航程/呼叫号编辑（桌面版 ContainerIdClass：TextName/TextClass/TextNumber/PopupSide/PopupUnitType）
    var name by remember { mutableStateOf(unit.name) }
    var unitClass by remember { mutableStateOf(unit.unitClass) }
    var numberText by remember { mutableStateOf(unit.number.toString()) }
    var side by remember { mutableStateOf(unit.side) }
    var rangeText by remember {
        // R4 显示修复：优先毫米余额，避免 syncRangeIntFromMm 向下取整造成“剩余0.6海里显示为0”的假耗尽
        val disp = if (unit.range == -100000) ""
                   else if (unit.rangeNmMm >= 0) (unit.rangeNmMm / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(java.util.Locale.US, "%.1f", it) }
                   else unit.range.toString()
        mutableStateOf(disp)
    }
    var callsign by remember { mutableStateOf(unit.textTags.callsign) }
    // 类型大类（Domain）与子类型：改类型联动重新判定 Domain 显示区（高度/深度输入、参考点判定等）
    val registry = com.simplot.android.domain.registry.UnitTypeRegistry
    var editDomain: com.simplot.android.domain.registry.UnitTypeRegistry.Domain by remember {
        mutableStateOf(registry.domainOf(unit))
    }
    var type by remember { mutableStateOf(unit.unitType) }
    // G19：被动方位编辑（增删/编辑，桌面版 PassiveBearings 面板）
    var bearings: List<com.simplot.android.data.model.PassiveBearing> by remember {
        mutableStateOf(unit.passiveBearingArray?.map { it.copy() } ?: emptyList())
    }
    var bearingsDirty by remember { mutableStateOf(false) }
    // 删除三选弹窗状态（R-P2：桌面 DeleteUnit 确认 Remove/Show as Sunk/Cancel）
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // R9：Domain 判定（参考点无航向航速；浮标显示深度）——G13 改为编辑期可改（联动编辑态）
    val isReference = editDomain == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.REFERENCE_POINT
    val isSonobuoy = editDomain == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SONOBUOY
    // G13：Domain 联动显示区——飞机显示高度、潜艇/浮标显示深度（编辑期改 Domain 立即生效）
    val showAltField = editDomain == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.AIR || unit.isAircraft()
    val showDepthField = editDomain == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SUBSURFACE || isSonobuoy || unit.isSubmarine()
    var showName by remember { mutableStateOf(unit.textTags.tagName) }
    var showCS by remember { mutableStateOf(unit.textTags.tagCourseSpeed) }
    // G21：补全桌面 ContainerTextTags 9 项（模型 TextTags 字段已备，仅加 UI 开关；不改序列化键名）
    var showTrackNum by remember { mutableStateOf(unit.textTags.tagTrackNum) }
    var showUnitTypeTag by remember { mutableStateOf(unit.textTags.tagUnitType) }
    var showClassTag by remember { mutableStateOf(unit.textTags.tagClass) }
    var showAltTag by remember { mutableStateOf(unit.textTags.tagAltitude) }
    var showDepthTag by remember { mutableStateOf(unit.textTags.tagDepth) }
    var showCallsignTag by remember { mutableStateOf(unit.textTags.tagCallsign) }
    var addText by remember { mutableStateOf(unit.textTags.additionalText) }
    var sunk by remember { mutableStateOf(unit.showSunk) }
    // G20：主动传感器开关（桌面版 ContainerActiveSensors/ActiveRadar/ActiveSonar）
    var activeRadar by remember { mutableStateOf(unit.isActiveRadar) }
    var activeSonar by remember { mutableStateOf(unit.isActiveSonar) }
    // 可见性是因（勾选输入），渲染是果：编辑器回显直接读 PerceptionArray 是否含 SeenBySide，
    // 不走 FogOfWar.isVisibleTo 的 null->全可见兜底（否则裁判全量存档 43 个 null 全亮为两勾，误导为已设互盲）
    fun hasSeen(side: String) = unit.perceptionArray?.any { it.seenBySide.equals(side, true) } == true
    val isMistUnset = unit.perceptionArray == null
    // 保存用初始值：仅勾选变化才写 PerceptionArray，不把 null 污染成显式互可见
    // 未设迷雾：己方始终可见，对方默认不可见（取消勾选即启用互盲的因，落盘才生成 SpScn 分视角）
    val initialVisibleBlue = if (isMistUnset) unit.side == "Blue" else hasSeen("Blue")
    val initialVisibleRed = if (isMistUnset) unit.side == "Red" else hasSeen("Red")
    var visibleBlue by remember { mutableStateOf(initialVisibleBlue) }
    var visibleRed by remember { mutableStateOf(initialVisibleRed) }

    // 受限项（需求二：对可见单位的脱敏设置，取 Blue/Red 感知记录的值作为编辑状态，双视角）
    val blueRec = unit.perceptionArray?.firstOrNull { it.seenBySide == "Blue" }
    var showNameBlue by remember { mutableStateOf(blueRec?.showName ?: true) }
    var showCSBlue by remember { mutableStateOf(blueRec?.showCourseSpeed ?: true) }
    var showClassBlue by remember { mutableStateOf(blueRec?.showClass ?: true) }
    var showTypeBlue by remember { mutableStateOf(blueRec?.showAsType ?: "") }
    var showSideBlue by remember { mutableStateOf(blueRec?.showAsSide ?: "") }
    // G22：ShowAltitude/ShowDepth 受限项开关（桌面 ContainerPerception 变体；引擎 applyRestrictions 已支持）
    // 默认 true（与 FogOfWar 新建 Perception 一致）；高度/深度是否可用的“生效”判定在 RestrictedRow 的 enabled 与地图标签/领导线处处理，不应在勾选默认值上一刀切按有无属性隐藏
    var showAltBlue by remember { mutableStateOf(blueRec?.showAltitude ?: true) }
    var showDepthBlue by remember { mutableStateOf(blueRec?.showDepth ?: true) }
    val redRec = unit.perceptionArray?.firstOrNull { it.seenBySide == "Red" }
    var showNameRed by remember { mutableStateOf(redRec?.showName ?: true) }
    var showCSRed by remember { mutableStateOf(redRec?.showCourseSpeed ?: true) }
    var showClassRed by remember { mutableStateOf(redRec?.showClass ?: true) }
    var showTypeRed by remember { mutableStateOf(redRec?.showAsType ?: "") }
    var showSideRed by remember { mutableStateOf(redRec?.showAsSide ?: "") }
    var showAltRed by remember { mutableStateOf(redRec?.showAltitude ?: true) }
    var showDepthRed by remember { mutableStateOf(redRec?.showDepth ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(unit.name.ifEmpty { unit.idNum }) },
        text = {
            // 反馈⑨：内容超出屏幕时可滚动（此前无滚动，小屏/内容多时底部被截断）；U2：避让键盘
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()
            ) {
                // ── ① 身份区（在家分段扫视） ──
                Text("① 身份 · 类型与阵营", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                val domainOptions = com.simplot.android.domain.registry.UnitTypeRegistry.Domain.entries
                    .filter { it != com.simplot.android.domain.registry.UnitTypeRegistry.Domain.UNKNOWN }
                ShowAsDropdown(
                    label = "类型大类",
                    options = domainOptions.map { it.label to it.name },
                    selected = editDomain.name,
                    onSelect = { newDomainName ->
                        com.simplot.android.domain.registry.UnitTypeRegistry.Domain.entries
                            .firstOrNull { it.name == newDomainName }?.let { d ->
                            editDomain = d
                            type = registry.typesOf(d).firstOrNull() ?: ""
                            // #5（G13）修复：切到航空/水下大类时默认初始化高度/深度输入为 "0"，
                            // 保证判别字段非 null → isAircraft()/isSubmarine() 渲染判定生效
                            // （此前空输入不落盘 → altitude/depth 保持 null → 类型切换"看似生效实则不变"）
                            if (d == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.AIR && alt.isBlank()) alt = "0"
                            if (d == com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SUBSURFACE && depth.isBlank()) depth = "0"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                ShowAsDropdown(
                    label = "子类型",
                    options = registry.typesOf(editDomain).map { it to it },
                    selected = type,
                    onSelect = { type = it },
                    modifier = Modifier.fillMaxWidth()
                )
                ShowAsDropdown(
                    label = "阵营",
                    options = listOf("蓝方 (Blue)" to "Blue", "红方 (Red)" to "Red", "中立 (Neutral)" to "Neutral", "未知 (Unknown)" to "Unknown"),
                    selected = side,
                    onSelect = { side = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unitClass, onValueChange = { unitClass = it },
                        label = { Text("舰级 / 类型代码（如 DD/BB/CL）") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = numberText, onValueChange = { numberText = it },
                        label = { Text("编号 / 舷号") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = rangeText, onValueChange = { rangeText = it },
                    label = { Text("航程（海里，留空=无限）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = callsign, onValueChange = { callsign = it },
                    label = { Text("呼叫号（留空则显示名称）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                // ── ② 航行区 ──
                Text("② 航行 · 航向/航速/高度/深度/位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (!isReference) {
                    OutlinedTextField(
                        value = courseText,
                        onValueChange = {
                            courseText = it
                            it.toFloatOrNull()?.let { v -> course = v }   // 非法输入保留上次有效值
                        },
                        label = { Text("航向（度 0-360）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = speedText,
                        onValueChange = {
                            speedText = it
                            it.toFloatOrNull()?.let { v -> speed = v }   // 非法输入保留上次有效值
                        },
                        label = { Text("航速（节）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                if (showAltField) {
                    OutlinedTextField(
                        value = alt, onValueChange = { alt = it },
                        label = { Text("高度（米）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (showDepthField) {
                    OutlinedTextField(
                        value = depth, onValueChange = { depth = it },
                        label = { Text("深度（米）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }

                // R-P2：X/Y 位置编辑（桌面版各单位窗口有 X/Y 字段）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = xText, onValueChange = { xText = it },
                        label = { Text("X（海里）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = yText, onValueChange = { yText = it },
                        label = { Text("Y（海里）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }

                // G19：被动方位编辑区（桌面版 PassiveBearings：增删/编辑 Type/BeamLength/BeamWidth/Bearing/Emitter/Label/ShowAsSide）
                HorizontalDivider()
                Text("③ 被动方位 · 声呐/ES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                bearings.forEachIndexed { idx, b ->
                    key(idx) {
                        PassiveBearingRow(
                            bearing = b,
                            ownerUnit = unit,
                            allUnits = allUnits,
                            onChange = { updated ->
                                bearings = bearings.mapIndexed { i, old -> if (i == idx) updated else old }
                                bearingsDirty = true
                            },
                            onDelete = {
                                bearings = bearings.filterIndexed { i, _ -> i != idx }
                                bearingsDirty = true
                            }
                        )
                    }
                }
                TextButton(onClick = {
                    bearings = bearings + com.simplot.android.data.model.PassiveBearing()
                    bearingsDirty = true
                }) { Text("+ 添加被动方位") }

                HorizontalDivider()
                Text("④ 显示 · 标签与附加文本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TagCheckboxRow(
                    showName, { showName = it }, "名称",
                    showTrackNum, { showTrackNum = it }, "航迹号"
                )
                TagCheckboxRow(
                    showUnitTypeTag, { showUnitTypeTag = it }, "类型",
                    showClassTag, { showClassTag = it }, "级别"
                )
                TagCheckboxRow(
                    showCS, { showCS = it }, "航向航速",
                    showCallsignTag, { showCallsignTag = it }, "呼叫号"
                )
                TagCheckboxRow(
                    showAltTag, { showAltTag = it }, "高度",
                    showDepthTag, { showDepthTag = it }, "深度"
                )
                OutlinedTextField(
                    value = addText, onValueChange = { addText = it },
                    label = { Text("附加文本") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()
                Text("⑤ 可见性 · 感知与传感器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (isMistUnset) {
                    Text(
                        "未设迷雾：当前存档 PerceptionArray 为空，保存后两边都可见；取消一勾即启用互盲（保存后将生成 Blue/Red 分视角 SpScn）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
                if (unit.side == "Blue" || unit.side == "Red") {
                    Text(
                        "当前为裁判视角（Referee）：如需区分红蓝互盲，请分别保存 Blue/Red 存档验证。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visibleBlue, onCheckedChange = { visibleBlue = it })
                    Text("对蓝方可见")
                    Checkbox(checked = visibleRed, onCheckedChange = { visibleRed = it })
                    Text("对红方可见")
                }

                // G20：主动传感器开关（桌面版 ContainerActiveSensors/ActiveRadar/ActiveSonar；
                // 渲染标记已在 UnitRenderer 绘制，此处在编辑期暴露开关）
                HorizontalDivider()
                Text("主动传感器（桌面 ContainerActiveSensors）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = activeRadar, onCheckedChange = { activeRadar = it })
                    Text("主动雷达")
                    Checkbox(checked = activeSonar, onCheckedChange = { activeSonar = it })
                    Text("主动声呐")
                }

                // 受限项（脱敏）：红蓝双方视角各自的显示限制
                Text("受限项（蓝方视角）", style = MaterialTheme.typography.labelMedium)
                RestrictedRow(
                    showName = showNameBlue,
                    showCS = showCSBlue,
                    showClass = showClassBlue,
                    showType = showTypeBlue,
                    showSide = showSideBlue,
                    showAltitude = showAltBlue,
                    showDepth = showDepthBlue,
                    onName = { showNameBlue = it },
                    onCS = { showCSBlue = it },
                    onClass = { showClassBlue = it },
                    onType = { showTypeBlue = it },
                    onSide = { showSideBlue = it },
                    onAltitude = { showAltBlue = it },
                    onDepth = { showDepthBlue = it }
                )
                HorizontalDivider()
                Text("受限项（红方视角）", style = MaterialTheme.typography.labelMedium)
                RestrictedRow(
                    showName = showNameRed,
                    showCS = showCSRed,
                    showClass = showClassRed,
                    showType = showTypeRed,
                    showSide = showSideRed,
                    showAltitude = showAltRed,
                    showDepth = showDepthRed,
                    onName = { showNameRed = it },
                    onCS = { showCSRed = it },
                    onClass = { showClassRed = it },
                    onType = { showTypeRed = it },
                    onSide = { showSideRed = it },
                    onAltitude = { showAltRed = it },
                    onDepth = { showDepthRed = it }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sunk, onCheckedChange = { sunk = it })
                    Text("沉没")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // G13：身份/类型/阵营/Class/Number/Range/呼叫号应用（类型改 Domain 联动由模型 domainOf 重新判定）
                unit.name = name
                unit.unitClass = unitClass
                unit.number = numberText.toIntOrNull() ?: unit.number
                unit.unitType = type
                unit.side = side
                if (rangeText.isBlank()) { unit.range = -100000; unit.initRangeMmFromPersisted() }
                else {
                    val d = rangeText.toDoubleOrNull()
                    if (d != null) {
                        // R4：支持一位小数（如 0.6），写入毫米镜像并向下取整海里到 Int 存档键
                        val mm = (d * 1000).toLong().coerceAtLeast(0L)
                        unit.rangeNmMm = mm
                        unit.syncRangeIntFromMm()
                    } else { unit.range = -100000; unit.initRangeMmFromPersisted() }
                }
                unit.textTags.callsign = callsign
                // G19：被动方位写回（仅用户增删/编辑过才落盘，未动过的单位不新增键，保字节兼容）
                if (bearingsDirty) unit.passiveBearingArray = bearings.toMutableList()
                // 反馈⑨：航向 clamp 0-360；航速不限上限（仅保留下限 0，避免负航速）
                unit.setCourse(course.toDouble().coerceIn(0.0, 360.0))
                unit.setSpeed(speed.toDouble().coerceAtLeast(0.0))
                xText.toDoubleOrNull()?.let { unit.x = (it * 100000).toLong() }    // 海里→文件单位
                yText.toDoubleOrNull()?.let { unit.y = (it * 100000).toLong() }
                // P1-2 修复（G13 反向切换）：按当前选择的大类显式重置无关物理量——
                // 此前 showAltField 以 unit.isAircraft()（altitude 非 null）判定恒 true，
                // 且 alt 清空时 alt.isNotBlank() 为 false 不写回 → 飞机/潜艇永远改不回水面。
                // 逻辑抽为纯函数 UnitTypeRegistry.applyDomainDimensions（可 JVM 单测）。
                when (editDomain) {
                    com.simplot.android.domain.registry.UnitTypeRegistry.Domain.AIR -> {
                        com.simplot.android.domain.registry.UnitTypeRegistry.applyDomainDimensions(unit, com.simplot.android.domain.registry.UnitTypeRegistry.Domain.AIR)
                        if (alt.isNotBlank()) unit.setAltitude(alt.toIntOrNull() ?: 0)
                    }
                    com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SUBSURFACE -> {
                        com.simplot.android.domain.registry.UnitTypeRegistry.applyDomainDimensions(unit, com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SUBSURFACE)
                        if (depth.isNotBlank()) unit.setDepth(depth.toIntOrNull() ?: 0)
                    }
                    else -> {
                        // 水面/设施/车辆/参考点/浮标/陆地编队等：清空高度与深度（回归水面语义）
                        com.simplot.android.domain.registry.UnitTypeRegistry.applyDomainDimensions(unit, com.simplot.android.domain.registry.UnitTypeRegistry.Domain.SURFACE)
                    }
                }
                unit.textTags.tagName = showName
                unit.textTags.tagCourseSpeed = showCS
                unit.textTags.tagTrackNum = showTrackNum
                unit.textTags.tagUnitType = showUnitTypeTag
                unit.textTags.tagClass = showClassTag
                unit.textTags.tagAltitude = showAltTag
                unit.textTags.tagDepth = showDepthTag
                unit.textTags.tagCallsign = showCallsignTag
                unit.textTags.additionalText = addText
                unit.showSunk = sunk
                // G20：主动传感器开关写回（桌面 ContainerActiveSensors）
                unit.isActiveRadar = activeRadar
                unit.isActiveSonar = activeSonar
                if (visibleBlue != initialVisibleBlue || (isMistUnset && !visibleBlue)) {
                    com.simplot.android.engine.FogOfWar.setVisibility(
                        unit, "Blue", visibleBlue, unit.positionTimeCreated,
                        file = null
                    )
                }
                if (visibleRed != initialVisibleRed || (isMistUnset && !visibleRed)) {
                    com.simplot.android.engine.FogOfWar.setVisibility(
                        unit, "Red", visibleRed, unit.positionTimeCreated,
                        file = null
                    )
                }
                // 受限项写回 Blue/Red 感知记录（仅当该单位对该方可见且有记录时）
                writePerception(unit, "Blue", visibleBlue, showNameBlue, showCSBlue, showClassBlue, showTypeBlue, showSideBlue, showAltBlue, showDepthBlue)
                writePerception(unit, "Red", visibleRed, showNameRed, showCSRed, showClassRed, showTypeRed, showSideRed, showAltRed, showDepthRed)
                onApply(unit)
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = {
                    // R-P2：删除先弹三选确认（桌面 DeleteUnit：Remove/Show as Sunk/Cancel）
                    showDeleteConfirm = true
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                // G29：复制入口（桌面版 Copy Unit → 剪贴板，不再立即生成副本；由 Paste 放置）
                androidx.compose.material3.TextButton(onClick = {
                    onCopy(unit); onDismiss()
                }) { Text("复制") }
                // 反馈⑨：编辑菜单加「取消」（不应用、不删除，仅关闭），顺序：删除、取消、应用
                androidx.compose.material3.TextButton(onClick = { onDismiss() }) { Text("取消") }
            }
        }
    )

    // 删除三选确认弹窗（桌面版 DeleteUnit 语义）
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除 TN ${unit.trackNumber} x ${unit.number}  (${unit.name})") },
            text = { Text("选择操作：") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(unit); onDismiss()
                }) { Text("Remove") }
            },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteConfirm = false
                        onShowAsSunk(unit); onDismiss()
                    }) { Text("Show as Sunk") }
                    androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }
}

/**
 * 航向/航速数值格式化（去尾零，契约8）：217.0 → "217"，12.5 → "12.5"，0.0 → "0"，40.0 → "40"。
 * 纯函数（top-level），便于 JVM 单测。
 */
fun formatCourseSpeed(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** 「显示为类型」选项：真实类型（留空）→ 空串；其余值与 unitClass 简码一致（契约8）
 *  与 UnitRenderer.CWS_CLASS_CELLS 映射键集合对齐（含 CV 系列 / CG / CL / CA 别名） */
private val SHOW_TYPE_OPTIONS: List<Pair<String, String>> = listOf(
    "真实类型（留空）" to "",
    "BB" to "BB", "CG" to "CG", "CV" to "CV", "DD" to "DD", "FF" to "FF", "PC" to "PC",
    "LA" to "LA", "LC" to "LC", "LS" to "LS", "AR" to "AR", "AS" to "AS",
    "CL" to "CL", "CA" to "CA", "CC" to "CC"
)

/** 「显示为阵营」选项：真实阵营（留空）→ 空串 */
private val SHOW_SIDE_OPTIONS: List<Pair<String, String>> = listOf(
    "真实阵营（留空）" to "",
    "Blue" to "Blue", "Red" to "Red", "Neutral" to "Neutral", "Unknown" to "Unknown"
)

/**
 * 标签开关一行（G21：两个复选框一组，窄屏两列布局，桌面 ContainerTextTags 每项一个开关）。
 */
@Composable
private fun TagCheckboxRow(
    a: Boolean, onA: (Boolean) -> kotlin.Unit, aLabel: String,
    b: Boolean, onB: (Boolean) -> kotlin.Unit, bLabel: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = a, onCheckedChange = onA)
        Text("显示$aLabel")
        Checkbox(checked = b, onCheckedChange = onB)
        Text("显示$bLabel")
    }
}

/**
 * 受限项一行：显示名称/航向航速/级别 三个复选框 + 显示为类型/阵营两个下拉。
 * G22：补 ShowAltitude/ShowDepth 两个复选框（桌面 ContainerPerception 变体）。
 * 供蓝方/红方视角复用（契约8 红蓝双视角）。
 */
@Composable
private fun RestrictedRow(
    showName: Boolean,
    showCS: Boolean,
    showClass: Boolean,
    showType: String,
    showSide: String,
    showAltitude: Boolean,
    showDepth: Boolean,
    // 受限项5勾是否在地图真正起作用：编审校验用注释（名称/航向航速/级别/高度/深度均通过 UnitRenderer 标签与 속도지시선/精灵伪装 挂钩）
    hasAltitude: Boolean = true,
    hasDepth: Boolean = true,
    onName: (Boolean) -> kotlin.Unit,
    onCS: (Boolean) -> kotlin.Unit,
    onClass: (Boolean) -> kotlin.Unit,
    onType: (String) -> kotlin.Unit,
    onSide: (String) -> kotlin.Unit,
    onAltitude: (Boolean) -> kotlin.Unit,
    onDepth: (Boolean) -> kotlin.Unit
) {
    // 5 勾均有文字且与地图一致 → 三勾常显，高度/深度按有无该属性决定 enabled（置灰非隐藏，符合“仅对有该属性的单位类型生效，不应一刀切隐藏”）
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = showName, onCheckedChange = onName)
        Text("显示名称")
        Checkbox(checked = showCS, onCheckedChange = onCS)
        Text("显示航向航速")
        Checkbox(checked = showClass, onCheckedChange = onClass)
        Text("显示级别")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = showAltitude, onCheckedChange = onAltitude, enabled = hasAltitude)
        Text("显示高度", color = if (hasAltitude) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Checkbox(checked = showDepth, onCheckedChange = onDepth, enabled = hasDepth)
        Text("显示深度", color = if (hasDepth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        if (!hasAltitude && !hasDepth) {
            Text("（该类型无高度/深度属性，勾选在地图标签不生效）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ShowAsDropdown(
        label = "显示为类型",
        options = SHOW_TYPE_OPTIONS,
        selected = showType,
        onSelect = onType,
        modifier = Modifier.fillMaxWidth()
    )
    ShowAsDropdown(
        label = "显示为阵营",
        options = SHOW_SIDE_OPTIONS,
        selected = showSide,
        onSelect = onSide,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 写回感知记录（指定阵营）：记录存在且该方可见时更新受限项（G22：含 ShowAltitude/ShowDepth） */
private fun writePerception(
    unit: Unit,
    side: String,
    visible: Boolean,
    showName: Boolean,
    showCS: Boolean,
    showClass: Boolean,
    showType: String,
    showSide: String,
    showAltitude: Boolean,
    showDepth: Boolean
) {
    val per = unit.perceptionArray?.firstOrNull { it.seenBySide == side }
    if (per != null && visible) {
        per.showName = showName
        per.showCourseSpeed = showCS
        per.showClass = showClass
        per.showAsType = showType
        per.showAsSide = showSide
        per.showAltitude = showAltitude
        per.showDepth = showDepth
    }
}

/**
 * 「显示为类型/阵营」只读下拉（契约8：受限项原为文本输入框，应为选项）。
 * ExposedDropdownMenuBox + menuAnchor(MenuAnchorType.PrimaryNotEditable)：
 * material3 1.3.0（BOM 2024.09.03）API，MenuAnchorType 为新版必传参数。
 * 选中「真实类型/阵营」写回空串（=真实，与现语义一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)   // ExposedDropdownMenuBox/menuAnchor 在 material3 1.x 为实验 API
@Composable
private fun ShowAsDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> kotlin.Unit,   // 全限定：本文件 Unit 被 data.model.Unit 遮蔽
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.second == selected }?.first ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
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

/**
 * G19：被动方位单条编辑行（桌面版 PassiveBearings.CBearing 面板）。
 * 编辑 Type/BeamLength/BeamWidth/Bearing/Emitter/Label/ShowAsSide；
 * 每次改动即时经 [onChange] 上抛（父层就地替换，删除经 [onDelete]）。
 * 数值框非法输入保留上次有效值（与航向/航速同策略）。
 */
@Composable
private fun PassiveBearingRow(
    bearing: com.simplot.android.data.model.PassiveBearing,
    onChange: (com.simplot.android.data.model.PassiveBearing) -> kotlin.Unit,
    onDelete: () -> kotlin.Unit,
    ownerUnit: Unit? = null,
    allUnits: List<Unit> = emptyList()
) {
    var type by remember { mutableStateOf(bearing.type) }
    var bearingText by remember { mutableStateOf(formatCourseSpeed(bearing.bearing)) }
    var beamLenText by remember { mutableStateOf(formatCourseSpeed(bearing.beamLength)) }
    var beamWidthText by remember { mutableStateOf(formatCourseSpeed(bearing.beamWidth)) }
    var emitter by remember { mutableStateOf(bearing.emitter) }
    var label by remember { mutableStateOf(bearing.label) }
    var showAsSide by remember { mutableStateOf(bearing.showAsSide) }

    fun emit() = onChange(
        bearing.copy(
            type = type,
            bearing = bearingText.toDoubleOrNull() ?: bearing.bearing,
            beamLength = beamLenText.toDoubleOrNull() ?: bearing.beamLength,
            beamWidth = beamWidthText.toDoubleOrNull() ?: bearing.beamWidth,
            emitter = emitter,
            label = label,
            showAsSide = showAsSide
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (label.isNotBlank()) label else (if (emitter.isNotBlank()) "目标 $emitter" else "方位 ${formatCourseSpeed(bearing.bearing)}°"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 类型改为下拉：声呐 vs 电子支援
            ShowAsDropdown(
                label = "探测类型",
                options = listOf("声呐 (Sonar)" to "Sonar", "电子支援 (ES)" to "ES"),
                selected = if (type.equals("Sonar", true)) "Sonar" else "ES",
                onSelect = { type = it; emit() },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = bearingText, onValueChange = { bearingText = it; emit() },
                label = { Text("方位角（度 °）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = beamLenText, onValueChange = { beamLenText = it; emit() },
                label = { Text("探测距离（海里，0=无限）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = beamWidthText, onValueChange = {
                    beamWidthText = it
                    val newWidth = it.toDoubleOrNull()
                    if (newWidth != null && emitter.isNotBlank() && ownerUnit != null) {
                        val tgt = allUnits.firstOrNull { it.idNum == emitter }
                        if (tgt != null) {
                            val curBearing = bearingText.toDoubleOrNull() ?: bearing.bearing
                            val clamped = com.simplot.android.render.BearingRenderer.bearingOf(curBearing, emitter, newWidth, allUnits, ownerUnit)
                            bearingText = formatCourseSpeed(clamped)
                        }
                    }
                    emit()
                },
                label = { Text("波束宽度/误差角（度 °）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        // Emitter：剧本单位下拉选择（自动读取当前场景全部单位，排除自身）
        val currentEmitterName = allUnits.firstOrNull { it.idNum == emitter }?.let { u -> u.callsignOrName().ifBlank { u.idNum } }
        ShowAsDropdown(
            label = if (emitter.isNotBlank()) "目标单位（${currentEmitterName ?: emitter}）" else "目标单位（可选关联）",
            options = listOf("无目标关联（固定方位线）" to "") + allUnits.filter { u -> ownerUnit == null || u.idNum != ownerUnit.idNum }.map { u ->
                val disp = u.callsignOrName().ifBlank { u.idNum }
                "$disp (${u.idNum})" to u.idNum
            },
            selected = emitter,
            onSelect = { newEmitter ->
                emitter = newEmitter
                if (newEmitter.isNotBlank()) {
                    val tgt = allUnits.firstOrNull { it.idNum == newEmitter }
                    if (tgt != null && ownerUnit != null) {
                        val autoDeg = com.simplot.android.render.BearingRenderer.calcBearing(ownerUnit.x, ownerUnit.y, tgt.x, tgt.y)
                        if (autoDeg != null) {
                            val width = beamWidthText.toDoubleOrNull() ?: bearing.beamWidth
                            val randomized = com.simplot.android.render.BearingRenderer.randomizeBearingInBeam(autoDeg, width)
                            bearingText = formatCourseSpeed(randomized)
                        }
                        if (showAsSide == "Unknown" && tgt.side.isNotBlank()) {
                            showAsSide = tgt.side
                        }
                    }
                }
                emit()
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = label, onValueChange = { label = it; emit() },
            label = { Text("标签 / 备注") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        ShowAsDropdown(
            label = "目标阵营（决定线色：蓝/红/未知黄）",
            options = listOf(
                "未知 (Unknown - 黄色)" to "Unknown",
                "蓝方 (Blue - 蓝色)" to "Blue",
                "红方 (Red - 红色)" to "Red",
                "中立 (Neutral - 黄色)" to "Neutral"
            ),
            selected = showAsSide,
            onSelect = { showAsSide = it; emit() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
