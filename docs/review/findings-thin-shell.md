# 薄壳与可用性 全程序审阅（MainActivity / GameViewModel / 对话框 / 横竖屏）

> 审阅人：subagent（薄壳与可用性域）  
> 时间：2026-08-22  
> 基准：`MainActivity.kt` 926 行 + `GameViewModel.kt` 1456 行 + 14 个对话框/组件；运行视角（Scaffold 职责、状态流、横竖屏、拇指可达、不遮挡、对话框分组、设置回写）

---

## 1 结论总览

| 维度 | 评级 | 一句话结论 |
|---|---|---|
| 薄壳职责 | **A-** | MainActivity 已压至「SAF + 组合 + 转发」三职责，无业务；仅遗留 `pendingExport*` 暂存（可接受）与截图两方法（建议下沉 VM）。 |
| 状态流 | **A** | VM 持有全部可变状态，`revision` 单一重组源；clipboard / relocate / formationSpecs 状态语义清晰；Settings 分流正确。 |
| 横竖屏 | **B+** | 横屏三项（40dp TopBar / 96dp 右竖条 / bottomBar 释放）均落地；但存在「配置变更未测试」与「横屏竖条滚动高度」边缘风险，见缺陷。 |
| 不遮挡红线 | **A** | SelectedUnitBar 已根除；取消选中 5 条路径可用；测量/回放/编辑均不叠盖地图中心。 |
| 对话框分组与设置回写 | **A-** | 场景/编辑/视图/更多四分组合理；Settings 四段（控制/符号/图层/颜色）完整；G11 日志分流与 `#12/#13/#18` 三修复均已落地。 |
| 可用性/人体工学 | **B+** | HomeEmptyState 52dp 超大触控 + TurnControlBar 三段卡片 + 底部拇指可达；但 UnitEditSheet 与 SettingsDialog 仍有滚动/键盘遮挡隐患。 |

**整体：可发布；缺陷均为 B/C 级，无 A 级阻塞。**

---

## 2 薄壳职责（MainActivity）

### 2.1 已达标

- Activity 仅 14 个 SAF launcher（OpenDocument/OpenDocumentTree/CreateDocument）+ `pendingExportUnits/name` 过渡态 + `captureScreenshot/saveScreenshotToGallery`。其余 42 处业务调用均为 `vm.*` 转发，符合「瘦壳」契约。
- 分组溢出菜单 `TopActions = SceneMenu/EditMenu/ViewMenu/MoreMenu` 替代 15+ 横滚 Button，彻底解决「顶部硬滚遮挡地图」。
- `TextButtonRow` 保留空桩防合入编译失败并显式注释「禁止恢复横滚」—— 合并安全。

### 2.2 遗留/建议

| # | 项 | 级别 | 说明 |
|---|---|---|---|
| S-1 | `pendingExportUnits/name` 暂存于 Activity | **C 轻微** | 跨 `exportDir.launch(null)` 两次回调间的过渡态。旋转会丢（configChanges 已接管故不崩，但值丢失则导出回退到全量）。建议收至 VM：`vm.pendingExportUnits/name`，由 VM 在 `exportMovementOrders` 后自行清空；Activity 仅转发 `directoryUri`。 |
| S-2 | `captureScreenshot/saveScreenshotToGallery` 留在 Activity | **C 轻微** | 触及 `window.decorView` 与 `MediaStore`，放 Activity 有技术理由；但 `saveScreenshotToGallery` 内 `vm?.toast` 耦合 VM。若后续抽 `ScreenshotUseCase`，可纯化。当前不改亦可。 |
| S-3 | `openFile` MimeType 冗余 | **C** | `arrayOf("application/json","application/octet-stream","*/*")` 等价于 `*/*`。保留无害，记录而已。 |
| S-4 | `LandscapeCompactTopBar` / `TopAppBar` 分支在 `Scaffold.topBar` 内做 `LocalConfiguration.orientation` 读取 | **B 建议加测** | 方向由 `configChanges` 接管不会重建，读取的是 `LocalConfiguration.current` 的 composition 值而非 Activity 重建，语义正确；但需 Robolectric 横竖屏切换回归（目前无 MainActivity 横屏测试）。 |

---

## 3 GameViewModel 状态流

### 3.1 核心链路

```
File加载（loadScenario/loadScenarioFromDirectory/applyLoaded/newScenarioFile）
  → camera/mapRenderer 清理与拟合（clearMap + fileKey 触发 fitBounds）
  → settingsOwnedByFile 标记（#13：文件级设置不写全局）
  → formationSpecs.clear / 队列清空 / revision++

编辑/拖动（applyEdit / applyArcEdit / applyWaypointsEdit / relocateUnitInto / copy+pasta）
  → file.units 就地可变 + revision++ 驱动 SceneCanvas.drawEpoch 重绘
  → pasteUnitInto：nextIdFor + TrackCounter.allocate + ShiftWaypoints 同步位移

回放（ReplayEngine.buildTimeline → replayTimeline / replayTick / setReplayFrame）
  → SceneCanvas.replaying 分支渲染（单位浅拷贝 + 视图过滤）

回合（AdvanceTurnUseCase.execute → Do，undo/next → 门禁校验 + revision++）
  → rangeExhausted / finalWaypointQueue 排队弹 AlertDialog
  → autoSaveGate 门禁 + SaveAuto + SavePlayerSettings 回写
```

### 3.2 亮点

- **`revision` 单一重组源**：替代旧 `turnTick` hack，SceneCanvas 侧 `LaunchedEffect(tick){ drawEpoch=tick }` + draw 阶段显式快照读保证 compose-ui 1.7 下必重绘。
- **`settingsLoadedFromFile` 分流**（#13）：`applySettings/updateSettings` 仅非文件源时写 `SharedPreferences`，文件级设置仅内存生效；保存时由 `saveThreeFilesTo/autoSave/saveSetup` 回写 `player_settings.json`。解决了全局默认被场景级设置静默覆盖的历史坑。
- **`clipboardUnit` 深拷贝**（G29）：`Gson 往返 copy`，粘贴时 `pasteUnitInto` 再隔离 `IdNum/TrackNumber` 分配 + 航路点位移，防撞号完整。
- **`relocateUnitInto` / `pasteUnitInto` 顶层纯函数**：可 JVM 单测（`UnitPasteRelocateTest` 已覆盖）。
- **`formationSpecs` 内存注册表**：空队形不落盘，有成员才持久化；`applyLoaded/createNewScenario` 均 `clear()`，不产生幻影编队（#3 修复）。

### 3.3 需复核项

| # | 项 | 级别 | 说明 |
|---|---|---|---|
| VM-1 | `vm: GameViewModel?` 在 Activity `onCreate` 外以 `var vm` 持有 | **B** | SAF 回调读 `vm?.loadScenario`，依赖先走 `setContent{ viewModel() }` 赋值。冷启动时序正确（onCreate 内先 setContent 再返回），但 `vm` 为可空导致每处回调均 `?.`。建议改为 `by viewModels()` 委托，回调中 `viewModel.loadScenario` 直取（Activity 的 ViewModelStore 与 Compose 的 `viewModel()` 同一实例）。当前可空写法不崩，仅风格债。 |
| VM-2 | `autoSave()` 静默吞异常 | **C 主动设计** | 自动存档失败不 toast，避免打扰推演；`logError` 亦未写入（catch 空）。符合「不阻塞推演」预期，记录即可。 |
| VM-3 | `exportMovementOrders` 默认参数读 `file?.units` | **C** | Activity 兜底分支 `else vm?.exportMovementOrders(it)` 依赖 VM 内部的「全量 + 设置内玩家名」。正确，但 `file` 为空时 VM 侧 `return` 静默忽略，无 toast；Activity 侧兜底不提示。建议 VM 侧 `file==null` 时 `toast("请先打开场景")`。 |
| VM-4 | `clearMeasures` 在多处调用（measure 退出 / 场景切换 / 新场景） | **A 已正确但需守恒** | 退出测量清 `selectedUnitId + measureLog`；`Scaffold.bottomBar` 的测量按钮与右侧竖条、SceneCanvas onSelect 均会 `measureMode=false + clearMeasures`，链路一致。后续新增退出路径需同步清理，避免残留测量线贴在地图上。 |

---

## 4 横竖屏可用性

### 4.1 规格实现

| 规格 | 实现位置 | 落地 |
|---|---|---|
| 横屏 TopBar 40dp 紧凑 | `Scaffold.topBar` + `LandscapeCompactTopBar`（`height(40.dp)` + `Surface 40dp Row`） | ✅ 释放 ~24dp 纵向 |
| 横屏 96dp 右侧竖条 | `Row{ Box(weight 1f)+Surface(width 96.dp)}` + `verticalScroll+Arrangement.spacedBy(6.dp)` | ✅ 不挤纵向，Do/Undo/Next/测量/回放 + 选中条置顶 |
| 横屏 bottomBar 释放 | `if (!landscapeBottom && file!=null)` 真横屏不渲染 `BottomActionBar` | ✅ |
| SelectedUnitBar 不遮挡 | 已移除；竖屏画布下方轻量条 + 横屏右侧条顶 + BackHandler + SceneCanvas 空白点取消 | ✅ 红线达成 |
| 取消选中 | 5 路径：SceneCanvas 空白轻点→`onSelect(null)`；竖屏下方条「✕ 取消选中」；横屏右侧条顶按钮；EditMenu「✕ 取消选中：name」；`BackHandler` 返回键 | ✅ |

### 4.2 缺陷/风险

| # | 项 | 级别 | 复现/影响 | 建议 |
|---|---|---|---|---|
| L-1 | **横屏竖条高度未 `windowInsets` 感知** | **B** | 刘海/手势条机型右侧竖条底部按钮可能被系统条遮挡（竖条 `Surface` 无 `navigationBarsPadding` / `windowInsets`）。纵向已释放但横向安全区未处理。 | 竖条 `Column` 外包 `windowInsetsPadding(WindowInsets.navigationBars)` 或 `safeDrawing`；TopBar 已在横屏置 `WindowInsets(0.dp)` 需同步评估。 |
| L-2 | **横竖屏切换未自动化测试** | **B** | `AndroidManifest configChanges=orientation|screenSize|screenLayout|keyboardHidden` 自管旋转，不重建；`LocalConfiguration.orientation` 在 compose 内读取 correctness 依赖重组时序。目前仅人工验证。 | 新增 `MainActivity` Robolectric 横竖屏切换回归（旋转后 file/camera/revision/选中态保持；对话框不重叠）。 |
| L-3 | **竖屏 `BottomActionBar` 5 按钮在小宽屏挤压** | **C** | 5 个 `weight 1f` 在 360dp 宽屏每钮 ~65dp，文字 `Do/Undo/Next/测量/回放` 仍可读；但「退出测量/退出回放」长文案可能折行。 | 已在回放分支减至 2 钮；正常分支若后续加钮需改横滑或溢出。当前不改。 |
| L-4 | **TurnControlBar 收起态文案超长截断** | **C** | `展开控局条 ▼ · ${currentTurnTime} → ${currentPositionTime}` 在窄屏可能溢出；`TextButton fillMaxWidth` 无 `maxLines/overflow`。 | 加 `maxLines=1, overflow=Ellipsis` 或缩短为「控局条 · ${interval.display()}」。 |

---

## 5 对话框审阅（7 个）

| 对话框 | 文件 | 行数 | 结论 |
|---|---|---|---|
| **NewUnit** | `NewUnitDialog.kt` 155 | A。Domain/子类型同行省扫视；默认坐标=视野中心（#4 同源修复）避免 (0,0) 丢失；名称回退 `type→新单位` 合理。键盘 `Number` 已设。 |
| **NewScenario** | `NewScenarioDialog.kt` 126 | A。日期/时间双框 + `isValidScenarioStartTime` 实时校验禁用「创建」；`mapFileName` 三态显示；清除后回 TypeOfMap=0。 |
| **Waypoint** | `WaypointEditorDialog.kt` 302 | A-。futureWaypointArray 增删/编辑完整；G04 导入（精确/偏移）已接线；`viewModel()` 同实例取 `sourcePool` 兼容但建议显式传参（见 W-1）。 |
| **Arc** | `ArcEditorDialog.kt` 203 | A。Sensor/Weapon 双列表；`copy()` 隔离；角度/距离数值输入 + `isVisible/isFilled` 开关；上下移位已做。 |
| **Formation** | `FormationDialog.kt` 287 | A-。G02 全接线：创建/重命名/删除/成员增删/设中心/类型/距离单位/准备/撤销；罗盘静态预览；中文标签 `formation*Label` 完整。 |
| **Convoy** | `ConvoyDialog.kt` 147 | A。G03 六字段：航向/航速/列行/列行距；默认 0 走环绕（#14 修复正确）；`verticalScroll` 防超屏。 |
| **Settings** | `SettingsDialog.kt` 337 | A-。四段分组清晰；符号集/尺寸下拉完整；6 色键 + Load/Save/Reset + ColorPicker 网格；G11 日志查看/清空。见 S-5。 |
| **ManualMove** | `ManualMoveSheet.kt` 158 | A。`DoMove/Pause/UndoMove + 档位` 对齐桌面；`initialSnapshot`/`undoStack` 以 `unit.idNum` 为 key；时间戳累进修复 #21。 |
| **UnitEdit** | `UnitEditSheet.kt` 743 | B+。最重对话框，功能完整但滚动与输入密度高；见 U-1。 |

### 5.1 对话框缺陷

| # | 项 | 级别 | 说明 |
|---|---|---|---|
| W-1 | WaypointEditor `viewModel()` 隐式依赖 | **C** | `sourcePool` 回退 `viewModel<GameViewModel>().file?.units`，依赖 MainActivity 同实例。若后续复用该 Dialog 于非 MainActivity 宿主会取错实例。建议调用点显式传 `allUnits = vm.file?.units ?: emptyList()`，已在 MainActivity 调用点未传参而走回退；收敛为显式更稳。 |
| S-5 | SettingsDialog `errorLog` 列表无滚动上限裁剪显示 | **C** | `errorLog` 底层 cap 200，但 `ErrorLogDialog` 内 `verticalScroll` 会渲染全部 200 条 Text，极端错误风暴下单帧绘制重。当前 200 上限可接受；若后续放宽需改 `LazyColumn`。 |
| S-6 | Settings `remember(settings)` 正确但 `pickingColorIndex` 未 key | **C** | 场景切换导致 `settings` 对象替换时 `pickingColorIndex` 仍保留旧选色弹窗索引，可能指向错键。概率低（切场景时设置弹窗通常已关）。可 `remember(settings){ mutableStateOf(null) }`。 |
| U-1 | UnitEditSheet 743 行单文件过长，超屏滚动与键盘重叠风险 | **B** | 内容含 9 项 Tag 开关 + 可见性 + 传感器开关 + 被动方位列表 + 坐标/名称/类型/阵营/航程/呼叫号，`verticalScroll` 已做但 AlertDialog 未设 `imePadding`，小屏弹键盘可能遮挡底部「保存/删除」。建议外层 `Modifier.imePadding()` 或将「保存/删除」固底。 |
| U-2 | UnitEdit/Arc/Waypoint 均 `AlertDialog` 而非 BottomSheet | **C 主动权衡** | AlertDialog 居中弹层在竖屏会遮挡地图中心，但符合「不遮挡红线」中的「不做覆盖地图的悬浮浮层」指已移除 SelectedUnitBar 覆盖；对话框属于显式编辑态遮挡是可接受的。记录为设计取舍。 |

---

## 6 家用人体工学

- **HomeEmptyState**：52dp 三钮（新建 52dp 满宽 + 打开文件/打开文件夹 52dp 并排），`Card 20dp 圆角 + 8dp 阴影`，文案「在沙发上也能单手开局」对居家场景友好；提示「文件夹=整目录授权」降低认知。达标。
- **BottomActionBar / TurnControlBar**：底部主操作 `8dp` 触控间距 + ` tonalElevation 6dp` 拇指可达；控局条三段卡片（时钟/时长/操作提示）「长时间盯屏扫描成本」已优化；可收起不挤地图。达标。
- **SceneCanvas 手势**：测量模式独占 `pointerInput`（`measureMode` 为 key 重启协程），非测量时 `draggingUnitId` 禁地图平移，解决 C1 冲突；长按阈值走 `viewConfiguration.touchSlop`。达标。

---

## 7 设置回写一致性

| 路径 | 写盘目标 | 是否走 gating |
|---|---|---|
| `updateSettings/toggleSetting/applySettings` | `SharedPreferences simplot_player_settings`（全局默认） | 非文件源才写（#13） |
| `loadScenario` 命中 `player_settings.json` | 内存覆盖 + `settingsLoadedFromFile=true` | 不写全局 |
| `createNewScenario` | `settingsLoadedFromFile=false` 复位 | — |
| `saveThreeFilesTo / saveThreeFilesToDirectory / autoSave / saveSetup` | 回写场景目录 `player_settings.json` | — |
| `savePlayerSettingsToScenarioDir` | 同上（显式入口） | — |
| `autoSave` | `autoSaveGate(enabled, hasFile, hasUri)` 门禁 | `G10=true` 默认开 |

**一致性结论**：全局（SharedPreferences）与场景级（`player_settings.json`）双轨已分流；`#13` 前 G55 的「文件设置静默覆盖全局默认」已闭环。SettingsDialog 的「保存」一次性 `onSave(s.copy(playerName))` 提交，符合「弹窗本地编辑→确认提交」语义。

---

## 8 遗留风险与建议优先级

| 优先级 | 项 | 动作 |
|---|---|---|
| **P1** | 横屏安全区（L-1）+ UnitEdit 键盘遮挡（U-1） | 竖条加 `navigationBarsPadding`；UnitEdit/Settings 根 `imePadding`。 |
| **P2** | 横竖屏切换回归测试（L-2） | Robolectric `MainActivity` 旋转保持性测试。 |
| **P2** | `pendingExport*` 收至 VM（S-1）+ `viewModels()` 去可空（VM-1） | 小重构，无行为变更。 |
| **P3** | Waypoint sourcePool 显式传参（W-1）、控局条文案截断（L-4）、reset 残留 colorPicker 索引（S-6） | 随手修复。 |

---

## 9 判定

**通过（可发布）**：薄壳职责、状态流、不遮挡红线、对话框功能完整性、设置双轨回写均已达标；横竖屏三项规格落地。缺陷均为 B/C 级，建议按 P1→P3 迭代消化，不阻塞当前版本。
