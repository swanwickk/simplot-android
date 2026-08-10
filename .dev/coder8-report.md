# Coder8 报告：契约8 四项修复实现

> 日期：2026-08-09 | 基线 v0.3.8（b214646） | 状态：✅ 完成，测试 + 构建全绿

## 改动清单（4 项，均在契约触碰文件内）

### 1. 按钮文字「计算」→「新位置」（MainActivity.kt）
- `TextButtonRow` 中 `Text("计算")` → `Text("新位置")`（L253），逻辑不变（`showCalcPosition = true`）
- 注释同步更新；对话框内「计算」按钮（L338，弹窗执行按钮）按契约非目标**未动**

### 2. CWS 默认 + 加载失败日志
- `GameViewModel.kt` L58：`symbolStyle` 默认 `SymbolStyle.NTDS` → `SymbolStyle.CWS`（打开存档即显示类型独特精灵图标；NTDS 仍可手动切换）
- `UnitRenderer.kt` `loadSprite`：
  - `appContext == null` → `Log.w(TAG, "sprite load failed: $name (appContext==null, UnitRenderer.init 未调用)")`，用 `AtomicBoolean` 标志位**仅首次**告警（防刷屏）
  - `assets.open` 异常 → `Log.w(TAG, "sprite load failed: $name", e)`（带堆栈）
  - 两种失败均仍 `return null` 走矢量兜底，不崩溃；新增 `TAG` 常量与 `AtomicBoolean` import
- 测试检查：`grep -rn "NTDS\|symbolStyle" app/src/test/` → **无任何断言引用默认 NTDS**（无需改测试）

### 3. 受限项「显示为类型/阵营」改下拉（UnitEditSheet.kt）
- 新增 `ShowAsDropdown` composable：`ExposedDropdownMenuBox` + `ExposedDropdownMenu` + `OutlinedTextField(readOnly=true, trailingIcon=ExposedDropdownMenuDefaults.TrailingIcon)`，标准 Material3 下拉外观
- 「显示为类型」选项：`真实类型（留空）` + BB/CC/DD/FF/PC/LA/LC/LS/AR/AS（选中「真实类型」→ 存 `""`）
- 「显示为阵营」选项：`真实阵营（留空）` + Blue/Red/Neutral/Unknown
- 写回逻辑不变：`bluePer.showAsType = showTypeBlue`、`bluePer.showAsSide = showSideBlue`（空串=真实）
- 选项常量 `SHOW_TYPE_OPTIONS` / `SHOW_SIDE_OPTIONS` 为文件内 top-level 私有

### 4. 航向/航速改数字输入（UnitEditSheet.kt）
- 新增 `courseText`/`speedText` 文本状态，初始 `formatCourseSpeed(unit.courseDeg()/speedKnots())` 去尾零（217.0 → 217）
- `OutlinedTextField(keyboardType=Number)`：label「航向（度 0-360）」「航速（节 0-40）」；输入 `it.toFloatOrNull()?.let { v -> course = v }`，非法输入保留上次有效值
- **Slider 保留为辅助**（在输入框下方）：拖动实时更新 `course/speed` 并同步 `courseText/speedText`
- 应用时 `unit.setCourse(course.toDouble().coerceIn(0.0, 360.0))`、`unit.setSpeed(speed.toDouble().coerceIn(0.0, 40.0))`（应用前 clamp）

### 纯函数（可单测）
- `UnitEditSheet.kt` 文件级 `fun formatCourseSpeed(v: Double): String`：`v % 1.0 == 0.0` → `v.toLong().toString()`，否则 `v.toString()`

## 测试结果
- 新增 `app/src/test/java/com/simplot/android/UnitEditSheetFormatTest.kt`（2 测试 6 断言）：
  - 217.0→"217"、12.5→"12.5"、0.0→"0"、40.0→"40" ✅
  - 217.5→"217.5"、0.5→"0.5"（补充小数保留）✅
- 全量：`./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL，89 测试全过、0 失败 0 错误**（87 基线 + 2 新增；契约任务描述中「+4」是按 4 断言估算，实为 2 测试 6 断言，覆盖了要求的全部 4 个断言）

## 构建结果
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**，`app/build/outputs/apk/debug/app-debug.apk`（10MB，12:22 生成）

## 遗留风险 / 注意事项
1. **⚠️ 内存瓶颈（重要，给后续轮次）**：本沙箱 cgroup 内存上限 2GB，openclaw-gateway 常驻 ~1.1GB。`testDebugUnitTest` + `assembleDebug` **合并一条命令跑会 OOM 杀掉 Gradle daemon**（首次尝试即崩：compile 成功但 daemon 中途消失）。**必须拆成两条独立命令顺序执行**（本次即此法通过）。不要 `--rerun-tasks` 全量重编译。
2. **ExposedDropdownMenuBox API 兼容性**：BOM 2024.09.03 → material3 1.3.0。`menuAnchor()` 无参版已废弃，必须用 `menuAnchor(MenuAnchorType.PrimaryNotEditable)`；且 `ExposedDropdownMenuBox`/`menuAnchor` 为 `@ExperimentalMaterial3Api`，已加 `@OptIn(ExperimentalMaterial3Api::class)`。`ExposedDropdownMenu` 是 scope 成员函数**不可 import**（误 import 会编译错，已修正）。若未来升 material3 2.x 需复核 API。**本方案编译通过、比 Box+DropdownMenu 更标准，未退回备选方案。**
3. **`Unit` 名称遮蔽**：UnitEditSheet.kt 中 `Unit` 指 `data.model.Unit`，下拉回调签名用全限定 `(String) -> kotlin.Unit` 规避。
4. 调试弹窗内 `onSelect` 回调只更新本地 state，真正写回发生在「应用」按钮（与原有输入框语义一致）。
5. 编译期仅剩一条**预存在**警告（SceneCanvas.kt:187「Condition is always 'true'」，非本轮引入）。
6. 未 commit（按流水线约定，主代理统一处理）。

## 验收对照（契约 §5，需真机手测）
- [ ] 打开存档默认 CWS：类型独特彩色图标直接显示，顶部按钮显示「NTDS」
- [ ] 「新位置」按钮 → 弹窗标题「新位置计算」正常
- [ ] 编辑单位：航向/航速为数字输入框（滑杆在下方辅助）
- [ ] 受限项：显示为类型/阵营为下拉，选「真实类型/阵营」存空串
