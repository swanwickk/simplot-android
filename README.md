# SimPlot Android（SimPlot2 安卓移植版）

将桌面尺规海战兵棋绘图工具 **SimPlot2**（Harpoon/鱼叉）移植到 Android，完全适配触摸屏与手机 UI。

> 当前版本：**0.1.0**（开发版） · 变更记录见 [CHANGELOG.md](CHANGELOG.md) · 版本号在 `app/build.gradle.kts`，**每次发布必须递增并同步更新 CHANGELOG**

> 本仓库为 **Kotlin + Jetpack Compose** 原生安卓实现。数据层与规则引擎由已验证的 Python 脚本（`scn_tool.py` / `simplot_cmd.py`）逐行转译，存档与桌面版**字节级兼容**。

## 核心功能

| 需求 | 说明 |
|---|---|
| 📁 四文件存档 | 保存裁判场景时自动生成 `Referee.json`（明文）+ `Blue.SpScn` + `Red.SpScn`（混淆）+ `player_settings.json`，可读取任意视角，与桌面版 `Scenarios/` 目录互通 |
| 🕵️ 感知迷雾 | 可设置单位对蓝/红方可见性；保存红蓝存档时按 `PerceptionArray` 过滤（剔除），可见敌方单位按受限项脱敏（名称/航向航速/级别等） |
| ⏱ 回合时间 | 回合时长自由填写 **XX分XX秒**，默认 3 分钟（`TurnInterval {Minutes, Seconds}`） |
| 👆 触摸交互 | 双指捏合缩放 / 单指拖拽平移 / 轻点选择 / 长按编辑（底部弹层） |
| ⚓ 规则引擎 | 鱼叉同步回合移动：转向前冲/分段/渐进式、加速两档（含 75% 加速档）、每 45° 转向损失、急舵、潜艇静航、Range 耗尽停船、完整 undo、回合回放 |
| 🗺 地图 | 无图网格 / 位图地图（PNG + txt 比例尺）/ **官方 MapMaker JSON 配置**（BoundaryRect、陆地多边形、标注、背景图自动加载） |

## 技术架构

```
app/src/main/java/com/simplot/android/
├── data/
│   ├── model/     # Scenario/Unit/Time/Turns 数据类（@SerializedName 对齐桌面 JSON 键序）
│   ├── codec/     # SpScnCodec（ASCII−1 混淆）+ JsonUtil
│   └── repo/      # ScenarioRepository（SAF 三文件读写）
├── engine/        # FogOfWar 感知过滤 / TurnState 回合状态机 / MovementEngine 运动计算
├── render/        # Camera 视口 / MapRenderer / UnitRenderer / TrackRenderer / ArcRenderer
└── ui/            # SceneCanvas 手势画布 / TurnControlBar 回合控制 / UnitEditSheet 单位编辑
```

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug
```

或用 Android Studio 打开本目录直接运行。

## 与桌面版互通

- 打开：SAF 选择 `.json`（明文）或 `.SpScn`（混淆）文件。
- 保存：选择场景目录，自动生成四文件（`File` 字段分别为 Referee/Blue/Red + player_settings）。
- 地图：`.json` 地图配置与场景同目录时自动加载（背景图 + 陆地多边形）。
- 桌面版保存一次后也会自动生成 Blue/Red，两者可互相替换使用。

## 兼容性说明

- `.SpScn` 编解码与桌面版**字节级一致**（JSON 明文 + `\x0c\t` 尾部 + 整体 ASCII−1 混淆）。
- 红蓝存档中"不可见单位"的落盘行为（剔除 vs 脱敏）默认按**剔除**实现；如需与特定桌面版行为完全一致，可在 `FogOfWar` 中调整。

## 路线图

- [x] P0 数据地基：模型 / 编解码 / 四文件读写 / 回合时长分秒
- [x] P1 渲染浏览：手势画布 / 网格 / 军标 / 轨迹 / 射程弧 / 官方地图
- [x] P2 功能复刻：单位编辑 / 回合机制 / 感知迷雾 / 回合时间 / undo / 回放
- [ ] P3 打磨发布：横竖屏适配 / 场景库管理 / 真机构建验证

## 许可

MIT
