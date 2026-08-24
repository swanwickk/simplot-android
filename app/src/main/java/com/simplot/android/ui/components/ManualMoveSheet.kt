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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.Unit
import com.simplot.android.engine.MovementEngine

/**
 * G15：手动移动控制弹层（桌面版 ContainerMove DoMove/Pause/UndoMove + PopupMoveSpeed 速度档位）。
 *
 * 入口：选中单位操作条「移动」按钮（UnitEditSheet 由并行子代理占用时的新增入口，避免同文件冲突）。
 *
 * 语义对齐桌面版：
 * - 速度档位 [MovementEngine.MANUAL_MOVE_GEARS]（0.5x/1x/2x/4x，应用在当前航速上）
 * - DoMove：沿当前航向移动「X 分钟 × 档位」，记录起点轨迹点、扣减 Range（E4）
 * - Pause：暂停（停止连续移动；点击「继续」恢复）
 * - UndoMove：撤销最近一步（位置/航速/航向/Range/轨迹点快照恢复）
 *
 * 缓冲式编辑：DoMove/UndoMove 即时就地修改 [unit]（弹层内可见），
 * 「应用」提交（onApply → VM revision++ 触发重绘）；「取消」恢复打开时的初始快照。
 */
@Composable
fun ManualMoveSheet(
    unit: Unit,
    currentTime: String,
    onApply: (Unit) -> kotlin.Unit,
    onDismiss: () -> kotlin.Unit
) {
    // 打开时快照：取消时整体回滚（与 UnitEditSheet 取消语义一致）
    // #17 修复：remember 加 unit.idNum key，弹层复用/切换单位时防止旧快照串入
    val initialSnapshot = remember(unit.idNum) { MovementEngine.snapshotOf(unit) }
    // UndoMove 栈：每步 DoMove 前压栈
    val undoStack = remember(unit.idNum) { mutableListOf<MovementEngine.ManualMoveSnapshot>() }

    var minutesText by remember { mutableStateOf("5") }
    var gear by remember { mutableStateOf(1.0) }
    var paused by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    // #21 修复：DoMove 轨迹点时间戳随步数推进（累计移动分钟），避免多次 DoMove 的
    // 起点轨迹点时间戳全部相同（每次 = 当前推算时间，逐步递增）
    var moveTime by remember(unit.idNum) { mutableStateOf(currentTime) }

    fun refreshPositions() {
        msg = "当前位置 X=${unit.x} Y=${unit.y}（Range=${unit.range}）"
    }

    fun doMove() {
        if (paused) { msg = "已暂停：点击「继续」恢复"; return }
        val mins = minutesText.toDoubleOrNull()
        if (mins == null || mins <= 0) { msg = "请输入有效的移动分钟数"; return }
        undoStack.add(MovementEngine.snapshotOf(unit))
        val moved = MovementEngine.manualMoveStep(unit, mins, gear, moveTime)
        if (!moved) {
            undoStack.removeAt(undoStack.lastIndex) // 勿用 removeLast()：JDK21 编译会绑定 SequencedCollection，Android API<35 崩溃
            msg = "无法移动：航速为 0 或航程已耗尽"
            return
        }
        // #21：每次成功 DoMove 推进轨迹时间戳（桌面手动移动按分钟推进时间语义）
        moveTime = com.simplot.android.data.util.TimeUtil.advance(moveTime, mins)
        msg = "已移动 ${formatGear(gear)} 档 × $mins 分钟 → X=${unit.x} Y=${unit.y}"
    }

    fun undoMove() {
        val snap = undoStack.removeLastOrNull()
        if (snap == null) { msg = "无可撤销步骤"; return }
        MovementEngine.restoreSnapshot(unit, snap)
        msg = "已撤销一步 → X=${unit.x} Y=${unit.y}"
    }

    AlertDialog(
        onDismissRequest = {
            // 点击外部 = 取消：恢复初始快照再关闭
            MovementEngine.restoreSnapshot(unit, initialSnapshot)
            onDismiss()
        },
        title = { Text("手动移动 — ${unit.name.ifEmpty { unit.idNum }}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()
            ) {
                Text(
                    "当前航向 ${formatNum(unit.courseDeg())}° · 航速 ${formatNum(unit.speedKnots())} 节 · " +
                        "Range ${if (unit.range == MovementEngine.RANGE_UNLIMITED) "∞" else unit.range} 海里",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it },
                    label = { Text("移动时长（分钟）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("速度档位（PopupMoveSpeed）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MovementEngine.MANUAL_MOVE_GEARS.forEach { g ->
                        TextButton(
                            onClick = { gear = g },
                            modifier = Modifier.padding(0.dp)
                        ) { Text(if (g == gear) "【${formatGear(g)}】" else formatGear(g)) }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = ::doMove) { Text("DoMove") }
                    TextButton(onClick = { paused = !paused }) {
                        Text(if (paused) "继续" else "Pause")
                    }
                    TextButton(onClick = ::undoMove) { Text("UndoMove") }
                }
                msg.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // 应用：就地修改已生效（DoMove 即时写入 unit），提交触发重绘
                onApply(unit)
                onDismiss()
            }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = {
                // 取消：恢复初始快照（撤销弹窗内全部移动）
                MovementEngine.restoreSnapshot(unit, initialSnapshot)
                onDismiss()
            }) { Text("取消") }
        }
    )
}

/** 档位显示：1.0 → "1x"；0.5 → "0.5x" */
private fun formatGear(g: Double): String =
    if (g % 1.0 == 0.0) "${g.toLong()}x" else "${g}x"

/** 数值显示：去尾零（与 UnitEditSheet.formatCourseSpeed 同策略） */
private fun formatNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
