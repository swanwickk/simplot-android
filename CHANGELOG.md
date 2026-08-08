# 更新日志 (Changelog)

版本号规则：开发版从 **0.1** 开始，每次发布递增（`MAJOR.MINOR.PATCH`，开发期 MAJOR=0）。
**每次 push 到 GitHub 前必须更新本文件 + `app/build.gradle.kts` 的 versionName/versionCode。**

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

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
