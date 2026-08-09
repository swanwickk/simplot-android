# Review7 审核报告（reviewer-bugfix7）

> 日期 2026-08-09 | 审核对象：coder-bugfix7 产物（v0.3.8 四项修复）| 契约：contract7.md v1.0
> 审核方式：逐文件静态审码（git diff + 未跟踪资源）+ 独立跑测试/构建

## 结论：✅ 批准（无阻塞项）

契约 4 项修复全部落实，无残留引用，87 测试独立复跑全绿，APK 构建成功且资源打包正确。
Coder 报告与落盘代码一致（其会话中止未影响产物完整性）。

---

## 逐条对照契约验收标准

### 修复 A：单位图标随 zoom 缩放（需求1）— ✅ 通过
| 契约要求 | 实现 | 核对 |
|---|---|---|
| `iconSizePx(zoom)` = 16f*(zoom/LABEL_BASE_ZOOM) clamp [14,40] | UnitRenderer.kt L48 完全一致 | ✅ |
| SceneCanvas 两处 draw 传 sizePx | L195（回放 frameUnit）、L204（正常单位）均传 `iconSizePx(camera.zoom)` | ✅ |
| hitTest hitRadius 随 zoom | L366 `hitRadius = max(20f, iconSizePx(zoom) * 1.2f)`，默认 zoom 下 =20f 与原行为连续 | ✅ |
| hitTest 接 zoom 参数，调用处传 camera.zoom | 函数 L365 带默认参 `zoom: Float = camera.zoom`；**3 处**调用（L132 轻点/拖拽分支、L145 onTap、L149 onLongPress）全部传 camera.zoom。契约写"2 处"为低估，实际 3 处全部更新，正确 | ✅ |

### 修复 B：选中单位 → 显性编辑入口（需求2）— ✅ 通过
- `SelectedUnitBar`（MainActivity L267-289）：`selectedUnitId` 对应单位存在 **且** 非测量模式 **且** 非回放时显示（L122-129 条件），完全符合契约"非测量非回放"语义
- 「编辑」→ `vm.editUnit = selUnit`（复用现有 UnitEditSheet 弹窗路径）✅；「取消选中」→ `vm.selectedUnitId = null` ✅
- 长按编辑路径保留（SceneCanvas onLongPress → onLongPress(hit) 原链路未动）✅
- 显示内容 = 单位名 + 类型（`${unit.name}（${unit.unitType}）`），单行省略号防溢出 ✅
- 新增 Surface/TextOverflow import，无编译问题 ✅

### 修复 C：CWS 独特类型图标（需求3）— ✅ 通过
- `CWS_CLASS_CELLS`：BB/CC/DD/FF/PC/LA/LC/LS/AR/AS → row1 格位 (1,2)~(1,11)，**别名 CL/CA → CC 格 (1,3)**，与契约完全一致 ✅
- `spriteFileName(side)`：Blue/Red/Neutral → 对应 png，其余（Unknown/未知）→ unknown_color_filled.png ✅
- `loadSprite`：ConcurrentHashMap 懒加载缓存，assets.open + decodeStream，异常返回 null 不崩溃 ✅（只按当前阵营解码单张，非 4 张全载）
- `drawCwsIcon`：src Rect 按 65px 网格裁剪（CWS_GRID=65f，1560x455=24x7 实测吻合），dst RectF 缩放至 sizePx 正方形；未命中映射或加载失败返回 false ✅
- `draw()`：`if (!(cws && drawCwsIcon(...)))` → CWS 先精灵图、false 走矢量兜底（原填充符号），任何类型都有可见符号 ✅
- 选中高亮画在 draw() 末尾（精灵图标之上外圈），顺序正确 ✅
- `MainActivity.onCreate` 调 `UnitRenderer.init(applicationContext)`；appContext 为 null 时安全降级矢量兜底 ✅
- 资源：4 张 png 均为 1560x455（实测确认），已打包进 APK ✅
- 兜底边界：CV/SS/DDG 等未映射类型走矢量，与契约"保守"策略一致 ✅

### 修复 D：去掉示例功能（需求4）— ✅ 通过
- MainActivity「示例」按钮删除，注释更新（L235）✅
- GameViewModel `loadSample()` 删除、ScenarioRepository `loadFromAssets()` 删除 ✅
- **残留引用检查**：`grep loadSample/loadFromAssets app/src/` → **0 结果** ✅
- assets/scenarios/ 目录整体删除（2 个 json 已从 git 删除）✅
- APK 内容核验：`assets/symbols/` 4 张 png 在包内，`scenarios` 条目 **0 个** ✅
- 仅剩"示例"字样为无关文档注释（SpScnCodec.kt 的"混淆示例"指加密样例，非示例功能）✅

### 测试（契约"新增/更新测试"）— ✅ 通过
- `UnitRendererTest` 新增 `icon size scales with zoom`：16f@默认 / 40f@上限 / 14f@下限（契约 3 断言合并 1 方法，可接受）
- 契约提示"需检查 GameViewModelTest 是否引用 loadSample"→ 已确认无任何残留引用

---

## 测试/构建证据（reviewer 独立复跑）

1. **首次** `./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=1`：BUILD SUCCESSFUL in 23s（任务 up-to-date，系主代理已跑缓存）
2. **强制复跑测试**（`cleanTestDebugUnitTest` + `testDebugUnitTest`，删除旧结果后真实执行）：
   - **BUILD SUCCESSFUL in 26s**，23 actionable tasks: 3 executed
   - 结果 XML（时间戳 10:53:02，本次全新生成）：
     - **12 suites / 87 tests / 0 failures / 0 skipped**（86 基线 + 1 新增）
     - 新测试 `icon size scales with zoom` 确认在 UnitRendererTest.xml 中
3. **assembleDebug**：APK `app-debug.apk` 10,010,777 字节，zip 核验含 4 张 symbols png、无 scenarios
4. 内存说明：--rerun-tasks 全量重编译触发过一次 daemon 崩溃（环境仅 ~580MB 可用），与 coder 报告"首次构建 daemon 崩溃"同因；增量/定向重跑稳定通过，非代码问题

---

## 阻塞项

无。

## 非阻塞建议（后续轮可选）

1. **CWS 精灵图标深色海图对比度**（契约已列为"待真机确认"）：blue 阵营深蓝图标在深蓝海面可能欠清晰，真机验证后如需要可加白描边或半透明底衬
2. `drawCwsIcon` 命中精灵图时未绘制 NTDS 语义的"选中外圈"以外的辅助元素（速度线/传感器标记仍画）——已核对速度领导线与传感器标记在 draw() 内独立绘制，不受影响，无问题
3. 命中区与视觉图标在最小 zoom 下有轻微不匹配（图标 14f 但 hitRadius 保持 20f 下限）——对触摸友好（命中区 ≥ 视觉），是有意设计，保持即可
4. CV/SS/DDG 等未映射类型的 CWS 图标可后续轮按 row2 潜艇/水雷等图案扩展映射表

## 审核结论

- **批准发布**。契约验收标准 4/4 达成，代码质量良好（防御性降级、懒加载、缓存、无死代码），测试与构建证据充分。
- 手工真机验收项（图标随缩放、选中操作条、CWS 彩色图标、无示例按钮）仍需按契约"验收标准（手动，真机）"执行，建议由主代理安排真机冒烟。
