# SimPlot Android v0.3.8 四问题修复契约（contract8.md）

> 版本 v1.0 | 日期 2026-08-09 | 主代理根因确认后落盘 | 流水线第 8 轮
> 基线：v0.3.8（b214646），87 测试全绿

## 用户反馈（真机 v0.3.8，4 项）

1. **「计算」按钮是什么功能？**（按钮文字不直观，用户不知道用途）
2. **打开存档后单位没有显示对应的图标**（用户没切 CWS 或 CWS 图标未生效）
3. **编辑单位里「显示为类型」和「显示为阵营」应是选项，却变成输入框**
4. **编辑单位里航速航向应是输入，却变成滑动条**

## 根因（主代理已确认）

### 问题1：计算按钮
- MainActivity `TextButtonRow` 的「计算」按钮 → `vm.showCalcPosition = true` → `CalcPositionDialog`（新位置计算器，桌面版 ContainerNewPosition：选参考单位 + 方位角 + 距离 → 新坐标）
- v0.2.0 就有，非本轮新增；但按钮文字「计算」太泛，用户不理解
- **修复**：按钮文字「计算」→「新位置」（语义明确）；对话框标题已有「新位置计算」无需改

### 问题2：图标不显示
- **根因 A（主因）**：`GameViewModel.symbolStyle` 默认 `NTDS`——NTDS 只画描边几何符号（圆/三角/椭圆/方块），**类型独特精灵图标只在 CWS 模式绘制**（UnitRenderer.draw 中 `cws && drawCwsIcon(...)`）。用户打开存档默认看到 NTDS 符号，没有类型图标
- **根因 B（需防御）**：CWS 模式下精灵图加载链路：`UnitRenderer.init(applicationContext)`（MainActivity L81 ✅）→ `loadSprite` 按 side 选文件名 → `assets.open("symbols/<name>")`。assets 资源已拷入且 Reviewer 核验 APK 含 4 张 symbols（无 scenarios）。真机加载应正常，但若失败静默 fallback 矢量符号，无任何提示
- **修复**：
  - `GameViewModel.symbolStyle` 默认改为 `CWS`（打开存档即显示类型独特图标；NTDS 仍可手动切换）
  - `UnitRenderer.loadSprite` 加载失败时（appContext==null 或 assets.open 异常）打 Log.w 日志（android.util.Log），便于真机排查；仍返回 null 走矢量兜底不崩溃
  - 顶部按钮文字逻辑不变（当前 NTDS 显示「CWS」，当前 CWS 显示「NTDS」）

### 问题3：显示为类型/阵营应为选项
- `UnitEditSheet.kt` 受限项（蓝方视角）中 `showTypeBlue`/`showSideBlue` 用 `OutlinedTextField` 文本输入（label「留空=真实类型/阵营」）
- 桌面版为下拉选择（真实类型 / 指定类型 / 真实阵营 / 指定阵营）
- **修复**：改为下拉选择器（Material3 `ExposedDropdownMenuBox` + `ExposedDropdownMenu`）：
  - 「显示为类型」选项：`真实类型（留空）`、`BB`、`CC`、`DD`、`FF`、`PC`、`LA`、`LC`、`LS`、`AR`、`AS`（值与 unitClass 简码一致；选择「真实类型」存空串）
  - 「显示为阵营」选项：`真实阵营（留空）`、`Blue`、`Red`、`Neutral`、`Unknown`
  - 选中值写回 `bluePer.showAsType` / `bluePer.showAsSide`（空串=真实，与现语义一致）
  - 简化实现：用 `Box + OutlinedButton/TextField 只读 + DropdownMenu` 或 ExposedDropdownMenuBox 均可，coder 自选最简单可靠方案（注意 material3 版本 API 可用性，避免编译错误）

### 问题4：航向/航速应为输入
- `UnitEditSheet.kt` 航向/航速用 `Slider`（0-360 / 0-40 节），触摸屏滑杆难以精确设置（桌面版是数字输入框）
- **修复**：改为 `OutlinedTextField` 数字输入：
  - 航向：`KeyboardType.Number`，输入后 `course = value.toFloatOrNull() ?: course`，显示当前值；范围校验 0-360（越界 toast 或 clamp）
  - 航速：同上，0-40 节（clamp）
  - **保留 Slider 作为辅助**（滑杆 + 输入框并存：滑杆拖动实时更新输入框，输入框精确输入）——这是最贴合触摸屏的方案；若 coder 觉得布局复杂，可仅输入框（用户明确说"应当为输入"，输入框为硬性要求，滑杆可选保留）
  - 默认值显示：course 初始 `unit.courseDeg().toFloat()`、speed 初始 `unit.speedKnots().toFloat()`，格式化去尾零（如 `217.0` → `217`）

## 触碰文件
1. `app/src/main/java/com/simplot/android/ui/GameViewModel.kt` — symbolStyle 默认 NTDS → CWS（L58）
2. `app/src/main/java/com/simplot/android/render/UnitRenderer.kt` — loadSprite 失败打 Log.w
3. `app/src/main/java/com/simplot/android/MainActivity.kt` — 按钮文字「计算」→「新位置」
4. `app/src/main/java/com/simplot/android/ui/components/UnitEditSheet.kt` — 航向/航速输入框（±滑杆）；显示为类型/阵营下拉

## 新增/更新测试
- `UnitEditSheet` 为 Compose UI 无单测（现有惯例：纯逻辑才测）——本轮新增逻辑若抽纯函数则测：
  - 可抽 `UnitEditSheet` 格式化函数 `formatCourseSpeed(v: Double): String`（去尾零）→ 加 2-3 断言
- 回归 87 测试全绿（symbolStyle 默认值变更无测试引用 NTDS 默认？**需检查**；若有测试断言默认 NTDS 同步改）

## 验收标准（手动，真机）
1. 打开存档：单位直接显示类型独特彩色图标（CWS 默认生效），顶部按钮显示「NTDS」（当前 CWS）
2. 「新位置」按钮：点开弹窗标题「新位置计算」，选参考单位+方位+距离 → toast 显示新坐标
3. 点选单位→编辑：航向/航速为数字输入框（可键盘精确输入），滑杆可选保留
4. 受限项：显示为类型/阵营为下拉选项，选「真实类型/阵营」存空串，选具体值写回存档
5. 无崩溃、无回归（NTDS 切换仍正常）

## 非目标
- 不改 CalcPositionDialog 逻辑（仅按钮文字）
- 不改精灵图资源/映射表
- 不动引擎/存档/手势
