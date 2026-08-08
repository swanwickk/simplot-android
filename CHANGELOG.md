# 更新日志 (Changelog)

版本号规则：开发版从 **0.1** 开始，每次发布递增（`MAJOR.MINOR.PATCH`，开发期 MAJOR=0）。
**每次 push 到 GitHub 前必须更新本文件 + `app/build.gradle.kts` 的 versionName/versionCode。**

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.2.0] - 2026-08-08

功能补齐 + 交互修复。对照桌面版反编译分析（用户 2026-08-08 提供）完成 P1-P3 全部待办。

### 修复
- **地图手势失效**：`detectTransformGestures` 回调把 centroid 丢弃、`zoom != 1f` 浮点比较吞掉 pan（单指拖动恒走缩放分支）→ 改为阈值判断 + pan/zoom 同时处理 + 双指中心锚点
- **视野自适应错误**：fitBounds 硬编码 1000×1000 → 改为 onSizeChanged 按真实画布尺寸，仅新场景首次布局时执行（不覆盖用户手势）
- **回合时间/位置时间不刷新**：`file = f` 引用不变导致 Compose 不重组 → 引入 turnTick 状态驱动重组

### 新增
- **地图绘制层补齐**（桌面版 Z 序）：水域名 / 深度色带(5级) / 国家名 / 城市 / 国界线 / 地图边界框
- **高度/深度引擎**：ChangeAltitude/ChangeDepth 向航路点 AssignedAltDepth 趋近，Ascent/Descent 速率，单回合上限 180 米
- **航路点归档**：到达航路点后移入 PastWaypointArray（轨迹/回放）
- **编队移动**（Compass 模式）：成员相对中心单位按 FormationBearing/Distance 重定位
- **测量工具**：拖拽画线 + 实时方位/距离显示（桌面版 Measurement）
- **新位置计算器**：参考单位 + 方位角 + 距离 → 坐标（桌面版 ContainerNewPosition）
- **单位复制**：深拷贝 + 新 IdNum/TrackNumber + 2 海里偏移（陆上单位不复制传感器/武器）
- **护航队生成**：COMMODORE 居中 + 6 Merchant 环绕（2000 码均匀分布）
- **运动命令导出**：Movement - Player.json（桌面版 WindowExportOrders）
- **Range 耗尽三选弹窗**：继续移动 / 删除单位 / 停止单位（桌面版 HasRangeRemaining）
- **速度领导线**：单位前方与航速成比例的指示线（桌面版 SpeedLeaders）
- **回放倍速**：1x/2x/4x/8x（桌面版 PopupSpeed）

### 验证
- 33 个 JUnit 测试全过
- 非 Compose 层 kotlinc 编译通过；完整 Gradle 构建成功

## [0.1.1] - 2026-08-08

### 修复
- SceneCanvas 缺少 `ArcRenderer` import，导致 Gradle 真机构建失败（首次沙箱构建发现）。

## [0.1.0] - 2026-08-08

开发版首个版本。SimPlot 桌面版（Windows）的 Android 复刻，目标：读取/保存官方桌面版场景存档（JSON），支持回合制推演、海图显示与基本编辑。

### 新增
- **存档兼容（桌面版 2.3.9 格式）**
  - 场景顶层键序与官方存档逐字节一致（File/Scenario/TypeOfGame/Time/Turns/Overlays/Objects/Units/Formations）
  - Waypoint 对象结构（PastWaypointArray/FutureWaypointArray），空轨迹输出 `{}`
  - Unit 建模 SensorArray/WeaponArray（射程弧）、编队字段（IsInFormation/Formation*）
  - TurnInterval 大写键（Minutes/Seconds）
  - 四文件保存：referee + Blue + Red + player_settings.json
- **推演引擎**
  - 移动指令解析：具体航速 / 加速X节（≥45° 减半）/ boost·decel 能力表
  - A 级快慢判定 + 75% 加速档（maxSpeed 瞬态）
  - 极地行动转向表（harpoonv 总结：200/1、100/2）
  - Range 耗尽停船、新单位当回合不移动
  - 完整 undo（深拷贝快照回退）
  - 回合回放（ReplayEngine + ReplayBar，1s/帧）
- **海图渲染**
  - 单位军标（水面/潜艇/飞机/岸上）、航迹线、网格、比例尺
  - 传感器/武器射程弧（VB 颜色、整圆/扇形、填充/描边）
  - 官方 MapMaker JSON 地图配置（BoundaryRect 坐标 ×10 转换、陆地/覆盖多边形、文字标注、背景图自动加载）
- **战争迷雾**
  - 阵营视角隔离（Blue/Red 各自可见集合）
  - 可见敌方单位按感知受限项脱敏（名称/航向航速/级别/类型/阵营/高度/深度）
- **其他**
  - 场景文件选择（系统文件选择器）、内置示例场景（冰海巨兽、拉普拉塔河口海战）
  - 单位编辑面板（含受限项开关）

### 验证
- 33 个 JUnit 测试（引擎 24 + 编解码 5 + 存档往返 6，含官方 Iron Bottom Sound Referee 存档回归）
- 官方地图配置 Iron Bottom Sound JJWS1.json 解析验证通过
- 待真机 Gradle 构建验证（沙箱无 Android SDK）
