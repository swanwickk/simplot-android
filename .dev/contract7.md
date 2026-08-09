# SimPlot Android v0.3.7 三需求 + 去示例修复契约（contract7.md）

> 版本 v1.0 | 日期 2026-08-09 | 主代理根因确认后落盘 | 流水线第 7 轮
> 基线：v0.3.7（df09e7f），86 测试全绿

## 用户需求（真机反馈，4 项）

1. **单位图标不随放大而放大 → 非常难以选中**
2. **原版 SimPlot 可选中单位设置各种属性，app 里没有该功能**（注：app 已有长按 UnitEditSheet，但用户不知情/入口不显眼，需显性化）
3. **原版每种类型单位都有独特图片，app 里没有**（CWS 彩色图标缺失，当前只有几何符号）
4. **去掉示例功能**（顶部「示例」按钮 + loadSample + assets 内置场景）

## 根因（主代理已确认）

### 需求1：图标不随 zoom 缩放
- `UnitRenderer.draw(...)` 默认 `sizePx = 16f` 固定，SceneCanvas 调用处未传 zoom 相关参数 → 无论地图放大缩小图标恒 16f
- `hitTest` hitRadius = 20f 固定 → 图标不变大、命中区也不变，放大后视觉上单位"淹没"在地图里，难选中
- 对比：单位标签已随 zoom 缩放（labelTextSize 24f 基准 clamp[18,48]），图标却固定——不一致

### 需求2：属性编辑入口不显眼
- `UnitEditSheet` 已实现（航向/航速/高度/深度/标签/可见性/受限项/删除），但**仅长按触发**（MainActivity L132 onLongPress → vm.editUnit）
- 触摸屏用户习惯"点选→编辑"，长按发现性差；且点选目前只显示距离辅助线，无编辑入口

### 需求3：CWS 独特图标缺失
- 桌面版 CWS 符号系统 = **彩色图片图标**（CwsSymbols.GetSymbolSet 从 12 张精灵图取格）：
  - 精灵图：`blue/red/neutral/unknown_color_filled/unfilled.png`（1560x455，65px 网格 24x7，共 168 格）+ mono 版
  - 已实测确认（ASCII 渲染 + 放大图视觉识别）：**row1（第二行）= BB/CC/DD/FF/PC/LA/LC/LS/AR/AS 带字母圆形图标**；row0 = 单字母 A/B/C/D/F/H/J/K/P/R/S；row2 = 潜艇/水雷等无字母图案
  - app 当前只有 NTDS 几何符号（圆/三角/椭圆/方块）+ CWS 填充圆——无类型独特图片
  - 场景实际类型：BB/CL/CA/DD（**CL/CA 不在 row1，映射到 CC 格**）

### 需求4：示例功能
- MainActivity L223「示例」按钮 → `vm.loadSample("冰海巨兽.json")` → `repo.loadFromAssets`（assets/scenarios/ 下 2 个 json）

## 修复方案

### 修复 A：单位图标随 zoom 缩放（需求1）
`UnitRenderer.kt`：
```kotlin
/** 单位图标尺寸（随 zoom 等比缩放，与标签同链路）：默认 zoom 下 16f，clamp [14f, 40f] */
fun iconSizePx(zoom: Float): Float = (16f * (zoom / LABEL_BASE_ZOOM)).coerceIn(14f, 40f)
```
`SceneCanvas.kt` 两处 draw 调用传 `sizePx = UnitRenderer.iconSizePx(camera.zoom)`（回放/正常模式都要）
`SceneCanvas.kt hitTest`：`hitRadius = 20f` → 改为随 zoom：`hitRadius = max(20f, UnitRenderer.iconSizePx(zoom) * 1.2f)`（hitTest 需接收 zoom 参数，调用处 2 个传 camera.zoom）

### 修复 B：选中单位 → 显性「编辑」入口（需求2）
- `MainActivity.kt`：`selectedUnitId != null` 时（非测量模式），在 SceneCanvas 上方/下方显示一行选中操作条：
  - 左侧：单位名 + 类型
  - 右侧：「编辑」按钮 → `vm.editUnit = 选中单位`（复用现有 UnitEditSheet）+ 「取消选中」按钮 → `vm.selectedUnitId = null`
- 实现：MainActivity 已有 selectedUnitId state；在 Box 布局加一个条件显示的 Surface（顶部工具栏下方或底部），简单 Row 即可
- 交互语义：点选单位（显示辅助线）后可见编辑入口；长按编辑路径保留

### 修复 C：CWS 独特类型图标（需求3）
- **资源**：把桌面版 4 张 color_filled 精灵图拷入 `app/src/main/assets/symbols/`（blue/red/neutral/unknown_color_filled.png，共 4 张；先不做 unfilled/mono）
- **映射表**（UnitRenderer 内新增）：
  ```
  row1 索引: BB=(1,2) CC=(1,3) DD=(1,4) FF=(1,5) PC=(1,6) LA=(1,7) LC=(1,8) LS=(1,9) AR=(1,10) AS=(1,11)
  别名: CL→CC, CA→CC, CV→CC?（保守：CL/CA→CC；其余未知→null 用矢量兜底）
  ```
- **绘制**：CWS 模式且 unitClass 命中映射 → 从精灵图裁剪对应 65x65 格绘制（BitmapRegionDecoder 或 drawBitmap srcRect）；否则 fallback 现有矢量符号
- **侧色**：按 u.side 选图（Blue/Red/Neutral/Unknown → 对应 png；Unknown 阵营用 unknown）
- **尺寸**：绘制尺寸用 `iconSizePx(zoom)`（与修复 A 一致）
- 单次解码精灵图并缓存 Bitmap（内存优化：4 张 1560x455 ARGB ≈ 2.8MB/张，用 inSampleSize 或直接 decode 后按需裁剪）

### 修复 D：去掉示例功能（需求4）
- MainActivity L223 删除「示例」按钮
- GameViewModel `loadSample()` 删除（或保留但无调用——**决定：删除方法**，避免死代码）
- ScenarioRepository `loadFromAssets` 删除（无其他调用）
- assets/scenarios/ 下 2 个 json 删除（不再需要）
- 顶部按钮注释更新（去掉"示例"）

## 触碰文件
1. `app/src/main/java/com/simplot/android/render/UnitRenderer.kt` — iconSizePx + CWS 精灵图加载/裁剪/映射
2. `app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt` — draw 传 sizePx；hitTest 接 zoom
3. `app/src/main/java/com/simplot/android/MainActivity.kt` — 选中操作条 + 删示例按钮
4. `app/src/main/java/com/simplot/android/ui/GameViewModel.kt` — 删 loadSample
5. `app/src/main/java/com/simplot/android/data/repo/ScenarioRepository.kt` — 删 loadFromAssets
6. `app/src/main/assets/symbols/*.png` — 4 张新增
7. `app/src/main/assets/scenarios/*.json` — 2 个删除
8. 测试：`UnitRendererTest` 增 iconSizePx 断言（默认 zoom 16f、放大 40f 上限、缩小 14f 下限）；`UnitRendererTextSizeTest` 不动

## 新增/更新测试
- `iconSizePx(0.0015f)==16f`、`iconSizePx(0.05f)==40f`（上限）、`iconSizePx(0.0005f)==14f`（下限）
- 回归 86 测试全绿（删 loadSample 后无测试引用它——**需检查**；若 GameViewModelTest 有引用同步删）

## 验收标准（手动，真机）
1. 放大地图：单位图标明显变大（随 zoom），缩小回 14f 最小；点击命中区域同步变大，易选中
2. 点选单位：出现选中操作条（单位名+编辑+取消选中）；点「编辑」打开属性弹窗（航向/航速等），可改并应用
3. CWS 模式：BB/CL/CA/DD 显示不同彩色图标（蓝色阵营蓝图、红色阵营红图），与 NTDS 几何符号可切换
4. 顶部无「示例」按钮；打开按钮正常

## 非目标
- 不做 unfilled/mono 精灵图（后续轮）
- 不改 NTDS 几何符号
- 不改 UnitEditSheet 内容（入口显性化即可）
- 不动引擎/存档/CSV/手势

## 假设
- 精灵图裁剪以 65px 为格（已实测验证）；CWS 彩色图标在深色海图上可见性待真机确认（必要时后续加白描边）
- 内存：4 张精灵图解码后约 11MB，可接受（可按需懒加载单张）
