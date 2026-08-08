# Review5 审核报告：两个视觉反馈问题修复（contract5.md）

> 审核者：reviewer-bugfix5 | 日期：2026-08-09 | 基线：v0.3.5（af3fe65）
> 审核对象：coder5-report.md + 工作区未提交 diff（SceneCanvas.kt +42/-21）

## 审核结论：✅ 批准（Approve）

问题清单：**阻塞 0 / 非阻塞 0 / 提示级 4**（见下）。实现与契约逐条吻合，强制测试全部通过，Coder 报告属实。

---

## 一、正确性：两遍画法（STROKE 黑边 → FILL 白字）逐项核验

| 位置 | strokePaint | fillPaint | 判定 |
|---|---|---|---|
| A1 drawMeasureLine (L294-305) | BLACK, STROKE, 4f, 20f | WHITE, 默认 style(FILL), 20f | ✅ 正确 |
| A2 ②辅助线 (L234-250) | BLACK, STROKE, 4f, 17f, CENTER | WHITE, 默认 style(FILL), 17f, CENTER | ✅ 正确 |
| B drawScaleBar 文字 (L343-354) | BLACK, STROKE, 4f, 15f | WHITE, 默认 style(FILL), 15f | ✅ 正确 |

核验要点：
1. **Paint 复用无泄漏**：三处均为独立的 strokePaint / fillPaint 对象，同一块代码内不存在「一个 paint 先画线后画字」的复用（原 drawScaleBar 的根因已消除——线条与文字 paint 已分离）。
2. **fillPaint 未设 style 是否会导致描边失效/字被吞**：`Paint` 默认 style 即 `FILL`，fillPaint 上未设 strokeWidth（默认 0）对 FILL 无影响 → 白字实心绘制正常，不会被吞。画法正确：先画黑描边（描边以字形轮廓线为中心向两侧各扩 ~2f），白填充覆盖字形内部 → 白字黑边效果成立。
3. **坐标一致性**：三处两遍 drawText 均为完全相同的坐标（A1: midX/midY；A2: midX/textY；B: x0/y0-8f），无偏移，符合契约「同坐标」要求；旧 +2f 阴影偏移（A1）已删除。
4. **锚点逻辑**：A1 的 midX/midY 公式、A2 的 textAlign=CENTER 与中线定位公式 `midY - (n-1)*lineHeight/2 + 5f`、B 的 x0/y0 位置均未变。

## 二、契约符合度：逐条对照

| 契约条款 | 实现 | 判定 |
|---|---|---|
| A1: textSize 16f→20f | ✅ 20f | 通过 |
| A1: 黑描边(STROKE 4f)+白填充(FILL) 同坐标，去 +2f 阴影 | ✅ 完全一致 | 通过 |
| A1: 决定=纯白填充+黑描边（无红字） | ✅ 红字覆盖画法已整体删除 | 通过 |
| A2: textSize 13f→17f（两行） | ✅ 17f | 通过 |
| A2: 黑描边+白填充替换 3f 白描边方案 | ✅ BLACK STROKE 4f + WHITE FILL | 通过 |
| A2: 中线定位逻辑不变 | ✅ 公式未变（仅 lineHeight 值调整，见提示1） | 通过 |
| B: 文字 FILL 样式 + 显式 textSize 15f | ✅ 显式设置 | 通过 |
| B: 白字+黑描边同款两遍画法 | ✅ | 通过 |
| B: 白线 strokeWidth 2.5f | ✅ 独立 linePaint | 通过 |
| B: 两端竖线刻度（可选） | ✅ x0 与 x0+70f 处 y0±6f 竖线 | 通过 |
| B: 去 DKGRAY | ✅ 已全部移除 | 通过 |
| 仅触碰 SceneCanvas.kt，纯绘制 | ✅ git diff 仅此一文件 | 通过 |
| 无新增单测（契约约定） | ✅ 未新增 | 通过 |

## 三、回归风险

- `git status`：仅 `SceneCanvas.kt` 被修改（+42/-21），另两个未跟踪文件为 .dev 下的契约/报告文档，非代码。**手势/引擎/模型/存档/CSV 路径零触碰** ✅
- 调用点未变：drawScaleBar 仍在原位置调用（L264），drawMeasureLine saved 分支（L213/L260）未动，绘制顺序无变化 → 非测量模式/回放模式无回归风险 ✅
- 基线核对：HEAD = af3fe65 "Release v0.3.5"，与契约基线一致 ✅

## 四、强制测试实测（串行执行）

1. `./gradlew testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1` → **BUILD SUCCESSFUL in 56s**（22 tasks executed，全部强制重跑）
   - 实测：**83 tests, 0 failures, 0 errors**（12 个测试结果 XML 汇总，非仅信任 coder 报告）
2. `./gradlew assembleDebug --no-daemon --max-workers=1` → **BUILD SUCCESSFUL in 19s**，`app-debug.apk` 9,846,884 字节 ≈ **9.8 MB** 产出正常

说明：assembleDebug 各 task 显示 UP-TO-DATE（APK 为 coder 在相同未改动源码上的构建产物）；但 `--rerun-tasks` 测试轮中 `compileDebugKotlin` 已对当前源码全新编译并通过，编译正确性已独立验证。

## 五、Coder 报告真实性核对

| Coder 声称 | 实测 | 判定 |
|---|---|---|
| +42/-21 仅 SceneCanvas.kt | ✅ git diff --stat 完全一致 | 属实 |
| 83 tests, 0 failures, 0 errors | ✅ 独立汇总结果一致 | 属实 |
| app-debug.apk 9.8 MB | ✅ 9,846,884 B ≈ 9.8 MB | 属实 |
| 无未说明改动 | ✅ lineHeight 15f→20f 与 y0-6f→y0-8f 均在报告「遗留风险/备注」中披露 | 属实 |

## 问题清单

### 阻塞级：无

### 非阻塞级：无

### 提示级（不影响批准，供真机验收时留意）
1. **A2 lineHeight 15f→20f**（契约未显式列出的配套调整）：17f 字形 ascent+descent ≈ 20f，15f 行距确会两行重叠，20f 合理；两行块整体相对中线位移 ±2.5f（coder 已披露），属字号适配而非锚点改动——真机验收标准 2 时顺带目测两行垂直居中即可。
2. **B 文字 strokeWidth 4f @ 15f 字号**：相对字号比例偏粗，小字号下字形可能显"臃肿"，但可读性优先于美观，符合契约「最清晰」取向；若真机观感不佳下轮可降至 3f（纯样式微调）。
3. **B 文字基线 y0-6f→y0-8f**：为两端竖线刻度让位，纯外观微调，已披露；验收标准 3 时确认文字与刻度无压线即可。
4. **非本次改动**：SceneCanvas.kt L186 存在编译警告 "Condition is always 'true'"（改动前既有，超出本次 diff 范围，不阻塞）。

## 验收建议

契约验收标准 1-4 为真机手动项，本审核无法替代；建议合入后由主代理转交人工真机验收（重点：深色海图上 20f/17f 白字黑边可读性、右下角 15f 实心白字比例尺清晰度、非测量模式无回归）。
