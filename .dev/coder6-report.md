# Coder6 报告：字号整体加大修复（契约6）

> 日期 2026-08-09 | 编码者 coder-bugfix6 | 基线 v0.3.6（7406d6e，83 测试全绿）

## 改了什么

### 1. 字号函数整体加大（`UnitRenderer.kt` L35-36，1 行逻辑）
```kotlin
// 旧：16f * (zoom / LABEL_BASE_ZOOM)，clamp [12f, 40f]
// 新：24f * (zoom / LABEL_BASE_ZOOM)，clamp [18f, 48f]
fun labelTextSize(zoom: Float): Float = (24f * (zoom / LABEL_BASE_ZOOM)).coerceIn(18f, 48f)
```
- 基准 16f → **24f**（+50%）；下限 12f → **18f**（任何 zoom 下 ≥ 18f，明显大于按钮文字 ≈14sp）；上限 40f → **48f**
- 拉普拉塔默认 zoom≈0.0011：24 × 0.73 = 17.6 → clamp 到 **18f**（原 12f，+50%）

### 2. 测量/辅助线标签统一走 labelTextSize（`SceneCanvas.kt`）
- **drawMeasureLine 测量标签**（L296-307）：`textSize = 20f` ×2 → `labelSize = UnitRenderer.labelTextSize(camera.zoom)`（drawMeasureLine 已收 camera，无需改签名）
- **②点选辅助线标签**（L234-248）：`textSize = 17f` ×2 → `labelSize = UnitRenderer.labelTextSize(camera.zoom)`（同一 Composable 作用域，camera 直接可用）；`lineHeight = 20f` → `labelSize * 1.2f`（两行块排版随字号缩放，保持紧凑不重叠）
- **drawScaleBar 比例尺**（L348-358）：`textSize = 15f` ×2 → **20f**（比例尺固定不随 zoom，白字黑描边两遍画法保留）

### 3. 未动的部分
- drawUnitLabel 的 k/锚点偏移（labelScaleK 0.7..2.5 已适配放大后的字，契约 §4.4 明确不动）
- 手势/引擎/存档/CSV/Material3 按钮样式均未触碰

## 测试

### 更新：`UnitRendererTest`（既有断言同步新期望值）
- `labelTextSize(0.0015f) == 24f`（原 16f）；`labelTextSize(0.0007f) == 18f`（原 12f）；`labelTextSize(0.05f) == 48f`（原 40f）
- 极端 clamp：`labelTextSize(0.00001f) == 18f`、`labelTextSize(1f) == 48f`
- labelScaleK 断言不变（该函数未改）

### 新增：`UnitRendererTextSizeTest`（3 个断言，契约要求）
- `labelTextSize(0.0015f) == 24f`（默认 zoom）
- `labelTextSize(0.0011f) >= 18f`（拉普拉塔 zoom 不再小于按钮文字）
- `labelTextSize(0.01f) <= 48f`（放大有上限）

### 结果
```
基线：83 测试 0 失败（先跑确认）
改后：86 测试 0 失败 0 错误（83 + 新增 3）  ✅ 与契约预期一致
```

## 构建

命令（未改 gradle.properties，仅 CLI 传 `-Dorg.gradle.jvmargs` 缩小堆以适配 3.4GB 沙箱，见遗留风险）：
```
./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m"
BUILD SUCCESSFUL in 33s
产物：app/build/outputs/apk/debug/app-debug.apk（9.8 MB）
```

## 遗留风险

1. **构建偶发 OOM**：首次不加 CLI 堆参数时 Gradle daemon 在 `dexBuilderDebug` 被系统杀（沙箱 3.4GB 内存、无 swap，dex worker + daemon 叠加超限）；加 `-Xmx512m` 后稳定通过。建议后续构建固定走该 CLI 参数，或评估加大沙箱内存（不能改 gradle.properties 的约束下这是最小代价方案）。
2. **行高适配**：辅助线两行文本 lineHeight 已随字号 1.2 倍缩放，但文本 Y 起始偏移（`midY - 6f`、`+5f`）仍为固定像素；默认/拉普拉塔 zoom 下 18-24f 字号不重叠，极端放大（48f）时两行可能贴近中线，需真机验收第 6 条（辅助线两行不重叠）。
3. **真机验收**：测量/单位/辅助线字号统一后需人工确认（契约验收 1-6），尤其拉普拉塔场景 ≥18f 明显大于按钮文字、缩放上下限行为。
4. 编译期有一处既有 warning（SceneCanvas L186 `Condition is always 'true'`，回放分支），与本次改动无关，未处理。

## 改动文件清单
- `app/src/main/java/com/simplot/android/render/UnitRenderer.kt`（+2 -2）
- `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt`（+11 -7）
- `app/src/test/java/com/simplot/android/UnitRendererTest.kt`（+7 -7，期望值更新）
- `app/src/test/java/com/simplot/android/UnitRendererTextSizeTest.kt`（新增，3 测试）
