# SimPlot Android v0.3.6 字号仍太小（用户明确不满）修复契约（contract6.md）

> 版本 v1.0 | 日期 2026-08-09 | 主代理根因确认后落盘 | 流水线第 6 轮
> 基线：v0.3.6（7406d6e），83 测试全绿
> 用户原话：「字体大小问题还是很大，我说了很多次看不清，依然给的很小的字号，先把测量的字号增加到和单位名称同样大小，然后两者继续加大字号，加大到和按钮上的文字一样大小。」

## 反馈问题（用户已多次强调，必须一步到位）

测量距离/方位标签、点选辅助线标签、单位名称标签**全部太小看不清**。用户明确要求：
1. **测量字号 = 单位名称字号**（统一、同步缩放）
2. **两者一起加大到 ≥ 按钮文字大小**（Material3 按钮 labelLarge ≈ 14sp）

## 根因（主代理已确认）

- 单位名称标签：`UnitRenderer.labelTextSize(zoom) = 16f * (zoom/0.0015)` clamp [12f, 40f]——**拉普拉塔场景 zoom≈0.0011 时被压到 12f 下限**，比按钮文字（≈14sp）还小
- 测量线标签：固定 20f（SceneCanvas L298/302）；辅助线标签：固定 17f（L238/243）；比例尺：固定 15f（L347/351）——**均不随 zoom 缩放**，用户放大地图后显得更小
- 字号链路不一致：单位名称随 zoom、测量不随 → 「测量 < 单位」在放大时明显

## 修复方案（最小 diff，统一字号链路 + 整体加大）

### 1. 字号函数整体加大（UnitRenderer.kt）
```kotlin
fun labelTextSize(zoom: Float): Float = (24f * (zoom / LABEL_BASE_ZOOM)).coerceIn(18f, 48f)
```
- 基准 16f → **24f**（放大 50%）
- 下限 12f → **18f**（任何 zoom 下最小 18f，> 按钮 14sp）
- 上限 40f → **48f**（放大后仍可读，防过大）
- 拉普拉塔场景：24 × 0.73 = 17.6 → clamp 到 **18f**（原 12f，+50%）

### 2. 测量标签/辅助线标签统一走 labelTextSize（SceneCanvas.kt）
- **drawMeasureLine 标签**（L290-305）：`textSize = 20f` → `textSize = UnitRenderer.labelTextSize(zoom)`——**函数需传入 zoom 参数**（drawMeasureLine 已收 camera，可从 camera.zoom 取，无需改签名）
- **②辅助线标签**（L230-250）：`textSize = 17f` ×2 → `UnitRenderer.labelTextSize(camera.zoom)`（同 composable 作用域内 camera 可用）
- lineHeight 同步：17f→labelTextSize 后，`lineHeight = 20f` 改为 `labelSize * 1.2f`（保持两行块排版紧凑不重叠）

### 3. 比例尺文字加大（drawScaleBar）
- `textSize = 15f` ×2 → **20f**（固定，比例尺不随 zoom；白字黑描边两遍画法保留）

### 4. drawUnitLabel 锚点偏移 k 不动（labelScaleK 0.7..2.5 已适配放大后的字）

## 触碰文件
1. `app/src/main/java/com/simplot/android/render/UnitRenderer.kt` — labelTextSize 基准/下限/上限加大（1 行）
2. `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt` — drawMeasureLine 标签、②辅助线标签、比例尺字号改 labelTextSize/20f + lineHeight 适配

## 新增/更新测试
- `UnitRendererTest` 若存在 labelTextSize 断言 → 更新期望值（基准 24/下限 18/上限 48）
- 新增 1 个断言：`labelTextSize(0.0011f) >= 18f`（拉普拉塔 zoom 不再小于 18f）；`labelTextSize(0.0015f) == 24f`（默认 zoom）
- 回归 83 测试全绿

## 验收标准（手动，真机）
1. 画测量线 → 中点标签「X.X nmi 方位 X°」与附近单位名称**一样大**（统一 labelTextSize）
2. 拉普拉塔场景（默认 zoom）：单位名称、测量标签 ≥ 18f，**明显大于按钮文字**
3. 放大地图：所有标签随 zoom 等比放大（上限 48f），可读
4. 缩小地图：标签最小 18f 不塌缩
5. 比例尺「50 nmi」20f 清晰
6. 辅助线两行标签（名称 + 距离方位）不重叠

## 非目标
- 不改锚点偏移逻辑、不改手势/引擎/存档/CSV
- 不改 Material3 按钮自身样式

## 假设
- 用户「和按钮文字一样大小」= 至少不低于按钮文字；24f/18f 为显著大于 14sp 的安全值，若用户仍嫌小，下轮直接按倍数放大
- 拉普拉塔默认 zoom≈0.0011（历史场景），labelTextSize 公式 `24 × (0.0011/0.0015) = 17.6 → 18f`
