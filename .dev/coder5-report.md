# Coder5 变更说明：两个视觉反馈问题修复（契约 contract5.md）

> 日期：2026-08-09 | 编码者：coder-bugfix5 | 基线：v0.3.5（af3fe65）

## 改了什么（最小 diff，纯绘制层）

仅触碰一个文件：`app/src/main/java/com/simplot/android/ui/components/SceneCanvas.kt`（+42 / -21 行）。
未动手势 / 引擎 / 模型 / 存档 / 标签位置与锚点逻辑。

### 修复 A1：drawMeasureLine 标签放大 + 衬底（原 16f 白字 + 红字覆盖阴影）

- `textSize` 16f → **20f**
- 去掉旧的「白字 +2f/+2f 偏移阴影 → 同坐标盖红字」画法
- 改为统一两遍画法：`strokePaint`（`STROKE`、黑、strokeWidth 4f）先画一遍，`fillPaint`（`FILL`、纯白）同坐标再画一遍
- 定位：仍用 `midX / midY = 中点 - 14f`，textAlign 保持默认 LEFT（锚点逻辑未变，仅去掉 +2f 阴影偏移）

### 修复 A2：②点选单位辅助线标签放大 + 衬底（原 13f 白描边 3f + 深灰填充）

- `textSize` 13f → **17f**（两行：单位名 + 「X.X nmi X°」）
- `outlinePaint`：白色描边 3f → **黑色描边 4f**（换色/加粗）
- `fillPaint`：深灰 `rgb(60,60,60)` → **纯白**
- `lineHeight` 15f → **20f**（17f 字两行防重叠，属于字号适配；中线定位公式 `midY - (n-1)*lineHeight/2 + 5f` 未变）

### 修复 B：drawScaleBar 重写（原 STROKE 样式 paint 画字 → 空心 DKGRAY 字）

- **线条与文字 paint 分离**：`linePaint`（白色、`STROKE`、strokeWidth 2.5f）只画线——主横线 + 两端竖线刻度（`x0/y0±6f`，简单比例尺形制）
- **文字**：`strokePaint`（黑、`STROKE`、4f）+ `fillPaint`（纯白、`FILL`）两遍同坐标画「50 nmi」，**实心白字**，显式 `textSize = 15f`（原未设，继承默认）
- 文字基线从 `y0 - 6f` 微调到 `y0 - 8f`（给两端竖线刻度让出间距）
- **去掉 DKGRAY**

## 为什么这么改

- 反馈 1：字号小 + 无衬底，深色海图上对比度不足 → 放大 + 「黑描边 + 白填充」两遍画法在任何底色上都可读
- 反馈 2：`drawScaleBar` 把画线的 `STROKE` 样式 paint 直接复用给 `drawText` → 空心描边字；DKGRAY 低对比；未显式设 textSize → 整体糊 → 重写为实心白字 + 白线

## 测试结果

- 基线（改动前）：`testDebugUnitTest` + `assembleDebug` → BUILD SUCCESSFUL，**83 tests, 0 failures, 0 errors**
- 改动后：`testDebugUnitTest` + `assembleDebug` → BUILD SUCCESSFUL（44s），**83 tests, 0 failures, 0 errors**，`app-debug.apk`（9.8 MB）产出正常
- 无新增单测（契约约定：纯绘制无法 JVM 断言像素，回归现有 83 项即可）

## 遗留风险 / 备注

1. **视觉验收需真机**：字号 20f / 17f / 15f 为契约给定初值；若真机上仍偏小/偏大，下轮微调（不涉逻辑）
2. ②辅助线标签 `lineHeight` 15f→20f 是契约未显式列出的配套调整——17f 字若仍用 15f 行距会两行重叠；对两行整体相对中线的垂直位置有 ±2.5f 的影响，可视为字号适配而非锚点改动
3. 比例尺两端竖线刻度为「简单实现」（固定 6f 高、随横线颜色），若后续要贴真比例尺数值需另立需求
4. 仅样式层改动，无手势/引擎/存档路径受影响；非测量模式与回放模式绘制顺序未变
