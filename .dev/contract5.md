# SimPlot Android v0.3.5 两个视觉反馈问题修复契约（contract5.md）

> 版本 v1.0 | 日期 2026-08-09 | 主代理根因确认后落盘 | 流水线第 5 轮
> 基线：v0.3.5（af3fe65），83 测试全绿

## 反馈问题

1. **测量时显示的距离和角度字体太小看不清**：测量线标签（起点→终点「X.X nmi 方位 X°」）textSize=16f、点选单位辅助线标签（名称 + 「X.X nmi X°」两行）textSize=**13f**——均偏小，且无描边/背景衬底，深色海图上对比度不足。
2. **画面右下角有一块很糊的疑似比例尺看不清**：`drawScaleBar` 用 **STROKE 样式 paint 画文字**（`style = Paint.Style.STROKE` 复用给 drawText）→ "50 nmi" 是空心描边字；颜色 DKGRAY 低对比；未显式设 textSize（继承 Paint 默认，与 2f strokeWidth 组合后糊成一团）。

## 根因（主代理已确认，证据链完整）

- `SceneCanvas.kt` L293-299（drawMeasureLine 标签）：`textSize = 16f`，白字描边后盖红字——无黑底/无深色描边，浅色地图区域几乎不可读。
- `SceneCanvas.kt` L236-247（②辅助线标签）：`textSize = 13f` + 白描边 3f + 深灰填充——字号最小的一处。
- `SceneCanvas.kt` L324-333（drawScaleBar）：`paint.style = STROKE`（为画线设置）→ 同一 paint 调 `drawText("50 nmi")` → **空心字**；DKGRAY（0xFF444444）在深色海图上对比度极低；无 textSize 显式设置。

## 修复方案（最小 diff，纯绘制层）

### 修复 A：测量相关标签放大 + 加可读性衬底
统一做法：**白字 + 黑描边**（先 STROKE 画黑边再 FILL 画白字），任何底色可读。

1. **drawMeasureLine 标签**（L290-299）：
   - `textSize` 16f → **20f**；文字先黑描边（`style=STROKE, strokeWidth=4f, color=BLACK`）后白填充（`style=FILL, color=WHITE`），两遍 drawText 同坐标（去掉现有 +2f 偏移阴影法）
   - 保留红色主题：白字黑描边即可，或填充色 argb(255,255,220,200) 暖白——**决定：纯白填充 + 黑描边**（最清晰）
2. **②辅助线标签**（L236-247）：
   - `textSize` 13f → **17f**（两行：单位名 + 距离方位）
   - 同样黑描边 + 白填充（替换现 outlinePaint 3f 白描边方案）
   - 中线定位逻辑不变（仅字号/画法）

### 修复 B：比例尺重画（实心字 + 高对比）
`drawScaleBar`（L324-333）重写：
- 文字：**FILL 样式** + 显式 `textSize = 15f`，白字 + 黑描边（与修复 A 同款两遍画法）
- 线条：白色（FILL 样式画线即实线），strokeWidth 2.5f，两端加小竖线刻度更像比例尺（可选，简单加即可）
- 整体配色白/黑（海图上最清晰），去掉 DKGRAY

### 触碰文件
仅 `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt`（纯绘制函数改动，无逻辑变更）

## 新增/更新测试
无新增单测（纯绘制，无法 JVM 断言像素）；回归现有 83 测试全绿（不动手势/引擎/模型/存档）。

## 验收标准（手动，真机）
1. 画测量线 → 中点标签「X.X nmi 方位 X°」清晰可读（20f 白字黑边），深色/浅色海图区域都可读
2. 点选单位 → 辅助线两行标签（单位名 + 距离方位）清晰可读（17f）
3. 右下角比例尺「50 nmi」为**实心白字**，清晰可辨，线条明显
4. 非测量模式/回放模式无回归（绘制顺序未变，仅样式）

## 非目标
- 不改标签位置/锚点逻辑（用户未抱怨位置）
- 不改单位标签（drawUnitLabel 已用 labelTextSize 随 zoom 缩放，用户未抱怨）
- 不改手势/引擎/存档/CSV

## 假设
- 纯绘制改动，编译风险极低；字号 20f/17f/15f 为合理初值，若用户仍嫌小下轮再调
