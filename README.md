# ExtractionPoints · 搜打撤撤离点插件

> 适用：**Paper 1.20.1**（Paper 196 实测可加载）+ **WorldGuard 7.0.9**（实测挂钩成功）
> 前置：WorldGuard 7.0.9 / WorldEdit 7.2.x（软依赖：未装 WG 时插件不会崩，仅功能停用）

[![Minecraft](https://img.shields.io/badge/Minecraft-%s-green.svg)](https://papermc.io)
[![Paper](https://img.shields.io/badge/Paper-%s-blue.svg)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-%s-orange.svg)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 安装

1. 把 `ExtractionPoints-1.0.0.jar` 放进服务器 `plugins/` 目录
2. 重启服务器，生成默认 `config.yml`
3. 参考 `config-示例.yml` 修改撤离点配置后，执行 `/extract reload`

## 快速上手：划一个撤离点

```
# 1. 用 WorldGuard 圈出撤离区域（以木斧选中为例）
//pos1  //pos2
/rg define extract_harbor

# 2. 在 config.yml 的 extraction-points 下加节点（id 自取，区域名必须对应）

# 3. /extract reload 生效
```

## 三种撤离类型 + 丢包撤

### ① 常规撤离 `type: NORMAL`
- 玩家进入区域后**独立计时**，倒计时结束自动传送到安全区
- 离开区域倒计时**清零**，重新进入重新计时（每人独立，互不干扰）

### ② 拉闸撤离 `type: SWITCH`（类三角洲行动）
- 拉闸方式二选一：
  - 管理命令：`/extract switch <switchId> on`
  - 或对接你自己的闸门逻辑后调用插件命令
- 拉闸后开始**全局倒计时**（配置 `switch.global-countdown`）
  - 在区域内的玩家看到 BossBar 倒计时；离开区域 BossBar 消失，**倒计时继续**
  - 倒计时归零时，**仍在区域内**的玩家全部撤离
  - 结束后闸门自动复位（`reset-after-finish`），未撤离的玩家需重新拉闸
  - 计时基于**绝对时间戳**，服务器卡顿/玩家反复进出都不会造成时间漂移
- **拉闸警报**：只通知闸点周围 `settings.notification.radius`（默认 300 格）内的玩家，音效与文本可在 `switch.alarm` 自定义；不配坐标时自动以撤离区域中心为圆心

### ③ 物品撤离 `type: ITEM`
- 常规计时 + 必须持有指定物品；`consume: true` 撤离时消耗，`false` 仅需持有
- `name-contains` 可按显示名区分同名物品（如"保险箱钥匙"）

### ④ 丢包撤（正交开关，三种类型都能用）
```yaml
任何撤离点:
  after-extract:
    lose-backpack: true   # ← 加这一行
```
撤离时只保留：**快捷栏 9 格 + 护甲 4 格 + 副手 1 格**，主背包 **27 格全部清空**。
与 `clear-inventory: true`（全清）互斥，全清优先。

## 命令（权限 `extraction.admin`，默认 OP）

| 命令 | 作用 |
|---|---|
| `/extract list` | 列出全部撤离点 |
| `/extract info <id>` | 查看撤离点详情（含闸门状态/剩余时间） |
| `/extract here` | 查看自己当前所在撤离点与倒计时 |
| `/extract switch <id> [on\|off\|toggle]` | 手动拉闸/复位闸门（命令方块可用） |
| `/extract bind <switchId>` | 绑定拉杆：30 秒内右键世界中的拉杆即写入配置 |
| `/extract unbind <switchId>` | 清空某闸门的全部拉杆绑定 |
| `/extract start <id>` / `stop <id>` | 直接启停全局倒计时 |
| `/extract reset <id\|all>` | 重置运行状态（清计时/冷却/闸门） |
| `/extract force <玩家> <id>` | 强制某玩家立即撤离 |
| `/extract reload` | 重载配置 |

闸门状态落盘在 `plugins/ExtractionPoints/data.yml`，重启不丢失。

## 拉杆触发（推荐替代命令方块）

```
/extract bind power_plant_main
# 30 秒内右键你想绑定的拉杆，坐标自动写入 config.yml
```

- 玩家右键已绑定的拉杆即触发全局倒计时，**无需命令方块、无需权限**
- 拉闸结果（成功 / 冷却剩余 X 秒 / 已开启）**只发给触发者本人**，不会向任何人广播失败信息，不暴露闸门状态
- 成功时仍按配置半径向周围播放警报与文本（正常游戏设计）
- 一个闸门可绑多个拉杆；`/extract switch` 命令保持可用，两者互不影响

## 拉闸后执行控制台命令（可选）

在撤离点的 `switch` 节点下添加 `console-commands` 列表即可，**不写就完全不执行**：

```yaml
switch:
  id: "power_plant_main"
  # ...
  console-commands:
    - "effect give {player} minecraft:glowing 38 0"   # 拉闸者发光 38 秒
    - "tellraw {player} {\"text\":\"警报！\",\"color\":\"red\"}"
```

占位符：`{player}` 触发者（控制台触发时为 CONSOLE）、`{point}` 撤离点 id、`{switch}` 闸门 id、`{time}` 倒计时秒数。命令以控制台权限执行，出错不影响拉闸流程。

## 已实测项（Paper 1.20.1 + WG 7.0.9 沙盒环境）

- ✅ 插件加载、3 个示例撤离点解析
- ✅ WorldGuard 7.0.9 挂钩（含未装 WG 时的安全降级）
- ✅ `/extract list` / `info` 输出、丢包撤配置解析
- ⚠️ 玩家进出区域的完整撤离链路（BossBar/传送/扣物品）未做真人联机测试，建议你上线前用两个号过一遍：进区域 → 等倒计时 → 确认传送与物品扣除；拉闸点各进出一次确认 BossBar 显隐与全局计时不受影响。

## 常见问题

- **进去没反应**：检查区域名是否与 `region` 完全一致、`world` 是否匹配；用 `/extract here` 看当前位置识别到了哪些区域
- **BossBar 样式**：只有 5 种 `PROGRESS / NOTCHED_6 / NOTCHED_10 / NOTCHED_12 / NOTCHED_20`（写 `SOLID` 会回退默认）
- **警报声**：`switch.alarm.sound` 支持形如 `minecraft:block.bell.resonate` 的写法

## 源码

完整 Gradle 工程在 `/workspace/ExtractionPoints/`，`gradle build` 即可重新打包（JDK 17）。
