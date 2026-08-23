package com.simplot.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.TurnState

/**
 * 底部主操作栏（P0-3）：把卧室里够不到的顶栏高频动作下沉到拇指区。
 *
 * 原先 Do/Undo/Next/测量/回放全挤在 TopAppBar 的横向滚动 Button 堆里，
 * 竖屏下需左右扫才能找到，拇指到达距离过长。本栏固定在底部，单手可达。
 *
 * 门禁（PC 对齐）：Do 仅 DO_BEFORE/DO_NEXT 可用、Undo/Next 仅 DO_AFTER 可用；
 * enabled 直接绑定 TurnState.canDo/canUndo/canNext，禁用时灰显，与桌面版
 * ContainerTurn.PushNextTurn / PushUndoTurn 的可用态一致（非“存档只可读”一刀切）。
 */
@Composable
fun BottomCommandBar(
    replaying: Boolean,
    measureMode: Boolean,
    onDo: () -> Unit = {},
    onUndo: () -> Unit = {},
    onNext: () -> Unit = {},
    onMeasure: () -> Unit = {},
    onReplay: () -> Unit = {},
    modifier: Modifier = Modifier,
    file: ScenarioFile? = null
) {
    val state = file?.let { TurnState.detect(it) }
    val canDo = state?.let { TurnState.canDo(it) } ?: (file != null)
    val canUndo = state?.let { TurnState.canUndo(it) } ?: false
    val canNext = state?.let { TurnState.canNext(it) } ?: false
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (replaying) {
                FilledTonalButton(
                    onClick = onReplay,
                    modifier = Modifier.weight(1f)
                ) { Text("退出回放") }
            } else {
                FilledTonalButton(
                    onClick = onDo,
                    enabled = canDo,
                    modifier = Modifier.weight(1f)
                ) { Text("▶ Do") }
                OutlinedButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.weight(1f)
                ) { Text("↩ Undo") }
                OutlinedButton(
                    onClick = onNext,
                    enabled = canNext,
                    modifier = Modifier.weight(1f)
                ) { Text("✓ Next") }
            }
            OutlinedButton(
                onClick = onMeasure,
                modifier = Modifier.weight(1f)
            ) { Text(if (measureMode) "退出测量" else "测量") }
        }
    }
}
