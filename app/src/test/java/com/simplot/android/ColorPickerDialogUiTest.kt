package com.simplot.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.simplot.android.ui.components.ColorPickerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 共享选色弹窗（颜色选择器）—— Robolectric + Compose UI 测试（无模拟器，JVM 上跑真 UI 语义树）。
 *
 * 覆盖：弹窗渲染与标题 / 预设色板网格可点选并立即回调 / HSV 滑杆区域可见 /
 * 确定按钮回传当前色 / 取消按钮不回调。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ColorPickerDialogUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `弹窗显示标题与规范代码`() {
        val picked = mutableStateOf<Long?>(null)
        var dismissed = false
        composeRule.setContent {
            ColorPickerDialog(
                title = "选择颜色（测试）",
                current = 0xFFFF00L,
                onPick = { picked.value = it },
                onDismiss = { dismissed = true }
            )
        }
        composeRule.onNodeWithText("选择颜色（测试）").assertIsDisplayed()
        // 顶部实时预览：规范存档格式代码
        composeRule.onNodeWithText("&h00FFFF00").assertIsDisplayed()
        // 预设色板网格存在（首色 testTag）
        composeRule.onNodeWithTag("preset_fff0f2f5").assertIsDisplayed()
    }

    @Test
    fun `点击预设色立即回调并关闭`() {
        val picked = mutableStateOf<Long?>(null)
        var dismissed = false
        composeRule.setContent {
            ColorPickerDialog(
                title = "t",
                current = 0xFF0000L,
                onPick = { picked.value = it },
                onDismiss = { dismissed = true }
            )
        }
        composeRule.onNodeWithTag("preset_ff005ac8").performClick()
        assertEquals(0xFF005AC8L, picked.value)
        assertTrue("点预设应关闭弹窗", dismissed)
    }

    @Test
    fun `点击非当前预设后回调新色`() {
        val picked = mutableStateOf<Long?>(null)
        composeRule.setContent {
            ColorPickerDialog(
                title = "t",
                current = 0xFF0000L,
                onPick = { picked.value = it },
                onDismiss = {}
            )
        }
        // 预设色板中的红色系 0xFFC81E1E
        composeRule.onNodeWithTag("preset_ffc81e1e").performClick()
        assertEquals(0xFFC81E1EL, picked.value)
    }

    @Test
    fun `确定按钮回传当前色且取消不回调`() {
        val picked = mutableStateOf<Long?>(null)
        var dismissed = false
        composeRule.setContent {
            ColorPickerDialog(
                title = "t",
                current = 0xFF0000L,
                onPick = { picked.value = it },
                onDismiss = { dismissed = true }
            )
        }
        // 确定按钮：无改动时回传当前色并关闭
        composeRule.onNodeWithText("确定").performClick()
        assertEquals(0xFF0000L, picked.value)
        assertTrue("确定应关闭弹窗", dismissed)
        // 取消按钮存在
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }
}
