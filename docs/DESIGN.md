# frend 设计文档(项目蓝图 · 原文存档)

> 本文档是 frend 的原始设计,作为整个项目的路线图。落地时的取舍记录在 DEVLOG.md(例:目标版本从 1.20.1 调整为 1.21.1,理由见里程碑 1)。

下面是我会给 **frend** 做的完整设计。这里我把你说的 **FA** 先理解为 **Fabric API**;如果你想同时兼容 Forge / NeoForge,建议后期再用 Architectury 做多加载器抽象。Fabric API 本身就是 Fabric 模组常用的 hooks 和互操作库;Architectury 则专门用来抽象 Fabric 与 Forge 的差异,包括事件、网络、注册表等。

## 1. MOD 定位

**MOD 名:frend**
**类型:本地运行的 Minecraft 陪伴机器人 / 智能 NPC / 生存助手**
**目标版本:先做 Fabric 1.20.1,之后按分支适配 1.20.4、1.21.x**
**核心卖点:不是"命令机器",而是像朋友一样陪你生存、聊天、干活、打怪、探险、通关。**

我会把它设计成:
**本体是一个类玩家 NPC,行为像玩家,思考在本地,执行由游戏内 AI 控制,不依赖网络。**

重要边界:单机和自建服务器里可以做得很强;公开服务器上不应该隐藏机器人身份或绕过反作弊,否则容易变成作弊工具。

---

## 2. 可以参考的 GitHub 方向

**路径规划参考 Baritone。** Baritone 是 Minecraft pathfinder bot,已经有 1.20.1 Fabric / Forge 支持,也有 API;但如果直接使用或改代码,要注意它是 LGPL-3.0,需要遵守许可证。

**机器人行为参考 Mineflayer 生态。** Mineflayer 是一个高层 Minecraft bot API,支持实体追踪、方块查询、物理移动、攻击、库存、合成、箱子、挖掘和建造等能力;mineflayer-pathfinder 支持静态、动态、组合目标的自动寻路;mineflayer-collectblock 则把"找方块、选工具、挖掘、拾取、满背包放箱子"封装成更高层的采集逻辑。

**长期学习参考 Voyager。** Voyager 的思路很适合 frend:自动课程、不断增长的技能库、根据环境反馈和错误自我修正。它原本依赖 GPT-4,但 frend 可以把这套思想改成本地 LLM + 固定技能 DSL。

**离线聊天推荐 Ollama 或 llama.cpp。** Ollama 提供本地 REST API,可以让 MOD 或本地桥接服务调用本机模型;llama.cpp 的目标就是用较少配置在本地或云端运行 LLM 推理。

---

## 3. 总体架构

```text
frend-mod
├─ frend-core          // 实体、物品、配置、权限、事件
├─ frend-body          // 类玩家 NPC:血量、饥饿、装备、动画、背包
├─ frend-perception    // 感知世界:方块、怪物、玩家、危险、资源
├─ frend-memory        // 本地记忆:玩家偏好、基地位置、箱子索引、任务历史
├─ frend-planner       // 高层规划:GOAP / HTN / 行为树
├─ frend-path          // 寻路:A*、跳搭、挖方块、放方块、避岩浆
├─ frend-skills        // 技能库:挖矿、砍树、种田、打怪、下界、末地
├─ frend-chat          // 本地聊天:Ollama / llama.cpp / 无模型规则回复
├─ frend-ui            // 指令、GUI、任务面板、好感度、语音可选
└─ frend-safety        // 权限、服务器限制、防误伤、防拆家
```

最关键的设计原则:**LLM 不直接控制键鼠或游戏状态。**
本地模型只负责"理解你想干什么"和"像朋友一样说话",真正执行必须转换成安全的内部指令,例如:

```text
玩家:帮我去挖一些铁,别离家太远。
LLM 输出意图:
TASK_COLLECT_ITEM {
  item: "minecraft:iron_ingot",
  amount: 32,
  max_distance: 300,
  avoid: ["lava", "ancient_city"],
  return_home: true
}
```

然后由 `frend-planner` 和 `frend-skills` 执行。

---

## 4. frend 的核心能力设计

### A. 陪伴聊天

frend 要有三层聊天:

第一层是**快速规则回复**,不需要模型,比如"跟我来""停下""回家""把东西给我"。

第二层是**本地 LLM 聊天**,通过 `localhost` 调 Ollama 或 llama.cpp。这样没网也能聊,玩家隐私也保留在本地。

第三层是**游戏上下文记忆**,例如:

```json
{
  "player_name": "ceinsuie",
  "home_base": "Overworld 120 64 -300",
  "likes": ["建筑", "生存", "速通"],
  "danger_zones": ["-50 12 800 有岩浆湖"],
  "chests": {
    "ores": "Overworld 123 65 -298",
    "food": "Overworld 125 65 -298"
  }
}
```

聊天风格可以做成"朋友感":
会吐槽,会提醒,会安慰,会主动说"你血量低,先别冲",但不会一直刷屏。

---

### B. 自动干活

我会先做这些任务:

| 任务    | 实现方式                         |
| ----- | ---------------------------- |
| 砍树    | 找树 → 砍原木 → 补树苗 → 回家存箱子       |
| 挖矿    | 找矿洞 / 阶梯矿 → 避岩浆 → 插火把 → 满包回家 |
| 搜集物品  | 查询附近方块 / 生物 / 箱子 → 规划路线 → 采集 |
| 种田    | 判断成熟度 → 收割 → 补种 → 存粮         |
| 建简单结构 | 根据蓝图放方块                      |
| 守家    | 巡逻 → 攻击怪物 → 修补缺口             |
| 跟随玩家  | 保持距离 → 避挡路 → 低血撤退            |
| 搬运物品  | 箱子索引 → 分类存储                  |

其中"采集物品"可以借鉴 mineflayer-collectblock 的设计:自动找方块、选择工具、挖掘、拾取、满背包后存箱子。

---

### C. 会生存

frend 不能像普通 NPC 那样傻站着,必须有生存循环:

```text
每 tick / 每秒:
1. 检查生命值
2. 检查饥饿值
3. 检查装备耐久
4. 检查附近敌人
5. 检查危险方块:岩浆、虚空、仙人掌、细雪
6. 检查任务是否还值得继续
7. 必要时撤退、吃东西、换装备、回家
```

生存优先级:

```text
保命 > 保护玩家 > 完成任务 > 聊天 > 表演性动作
```

例如它正在挖钻石,但发现你被苦力怕围了,它应该先来救你,而不是继续挖矿。

---

### D. 自动路径规划

路径系统是 frend 的灵魂。我的设计是:

```text
FrendPathfinder
├─ 地面寻路:A*
├─ 挖掘寻路:允许破坏低价值方块
├─ 搭桥寻路:允许放方块跨峡谷
├─ 垂直寻路:水桶、电梯、挖楼梯、搭方块
├─ 下界寻路:避岩浆、避猪灵、避恶魂
├─ 战斗寻路:保持距离、绕背、卡盾
└─ 动态重规划:被怪物挡路、方块变化、玩家移动时重新规划
```

Baritone 已经证明这种方向可行,它支持到 1.20.1 Fabric / Forge,并提供了 API 和示例用法;frend 可以选择"依赖 Baritone API"或"自己实现轻量版寻路"。

我的建议:
**MVP 阶段先自己写轻量寻路;高级阶段再接 Baritone API 或重写 Baritone-like pathfinder。**

原因是 frend 不只是"走到某个坐标",它还要像人:会停顿、看你、让路、补火把、绕开你正在建的东西。

---

## 5. "像真人"的行为设计

"能让人分辨不出来他是一个机器人",我会把它理解成:**行为自然,不呆板,不瞬移,不机械重复。**

可以做:

```text
- 走路不是绝对最短路径,会轻微绕路
- 看向玩家时有平滑转头
- 打怪会后撤、格挡、吃食物
- 挖矿会插火把、检查背包、回头看玩家
- 聊天不会秒回,复杂问题有短暂停顿
- 会主动说"我背包快满了""我找到钻石了"
- 会记得你常用的家、矿洞、箱子
- 偶尔做小动作:蹲一下、挥手、点头、看风景
```

但不做"伪装成真人玩家去公共服务器骗人"的功能。单机里可以像朋友;多人服务器里应该有权限和标识。

---

## 6. 速通与击杀末影龙设计

这是后期大功能,可以拆成一条"剧情任务链"。

```text
SpeedrunDragonTask
├─ 木器时代:砍树、做工具
├─ 石器时代:挖石头、做炉子、做石镐
├─ 铁器时代:找铁、做桶、盾、铁镐
├─ 下界准备:食物、金装备、黑曜石、打火石
├─ 下界行动:找堡垒、找要塞、换珍珠、刷烈焰棒
├─ 回主世界:合成末影之眼
├─ 找要塞:三角定位 / 投眼追踪
├─ 进入末地:破水晶、躲龙息
└─ 击杀末影龙:弓箭 / 床爆 / 常规战斗
```

frend 可以提供三种难度:

```text
普通陪玩:你主导,它帮忙。
半自动:它做准备,你一起打。
全自动挑战:它自己尝试通关,但只限单机/测试世界。
```

"最速通关"不建议一开始就承诺世界纪录级别。正确目标是:
**能稳定通关,再优化路线。**

Voyager 的"技能库 + 自我修正"思路可以用在这里:第一次不会打龙,失败后记录原因,下一次选择更安全的策略。

---

## 7. 技能库 DSL 设计

frend 不应该让 LLM 直接写 Java 代码执行。应该定义安全 DSL:

```yaml
skill: collect_item
args:
  item: minecraft:diamond
  amount: 6
  max_distance: 600
  allow_mining: true
  allow_nether: false
  return_home: true
```

技能执行器只接受白名单技能:

```text
follow_player
guard_player
collect_item
mine_ore
chop_tree
farm_crop
deposit_items
craft_item
attack_hostile
build_bridge
go_home
explore_cave
find_structure
prepare_nether
fight_dragon
```

这样即使本地 LLM 胡说,也不会把世界搞坏。

---

## 8. 本地 AI 方案

推荐三档:

### 低配电脑

不用 LLM,靠规则树和模板聊天。

```text
优点:不卡、稳定、完全离线
缺点:聊天不够聪明
```

### 中配电脑

Ollama + 小模型,例如 3B / 7B 级别。

```text
MOD → 本地桥接服务 → Ollama localhost:11434 → 返回意图 JSON
```

Ollama 提供 REST API,可以很方便地从本地服务调用模型。

### 高配电脑

llama.cpp / Ollama + 更大模型 + 向量记忆。

```text
玩家聊天
→ 加载最近游戏记忆
→ 本地 LLM 理解
→ 输出 DSL
→ frend-planner 执行
```

llama.cpp 的定位就是让 LLM 在多种硬件上用较少配置运行推理。

---

## 9. 版本兼容策略

```text
第一阶段:
- Fabric 1.20.1(实际落地调整为 1.21.1,见 DEVLOG 里程碑 1)
- Java 17(实际落地为 Java 21)
- Fabric API
- 单人世界 / 局域网 / 自建服务器

第二阶段:
- 其他 MC 大版本按分支适配

第三阶段:
- Architectury 多加载器
- Fabric + Forge + NeoForge
```

---

## 10. MVP 开发路线

### v0.1:能出生、能跟随、能聊天

```text
- /frend summon
- /frend follow
- /frend stay
- /frend come
- /frend home set
- 简单聊天
- 类玩家模型
- 基础背包
```

### v0.2:能干活

```text
- 砍树
- 挖石头
- 挖煤和铁
- 回家存箱子
- 工具耐久判断
- 自动吃食物
```

### v0.3:能打怪和保护玩家

```text
- 攻击僵尸、骷髅、苦力怕
- 盾牌防御
- 低血撤退
- 玩家被攻击时支援
```

### v0.4:本地 LLM 接入

```text
- Ollama / llama.cpp 桥接
- 自然语言转任务
- 本地记忆
- 聊天人格
```

### v0.5:矿洞与下界

```text
- 火把策略
- 避岩浆
- 下界传送门
- 找堡垒 / 要塞
- 猪灵交易
```

### v1.0:完整陪伴生存

```text
- 长期记忆
- 任务队列
- 技能库
- 守家
- 联机权限
- 配置 GUI
```

### v2.0:末影龙与速通

```text
- 末影之眼找要塞
- 末地战斗
- 自动破水晶
- 龙战策略
- 半自动 / 全自动通关模式
```

---

## 11. 一句产品定义

**frend 不是外挂,不是刷材料机器,而是一个会记住你、陪你冒险、能帮你干活、能和你一起通关 Minecraft 的本地 AI 朋友。**

技术上最稳的路线:

```text
Fabric MOD
+ 本地 NPC 行为树
+ Baritone-like 寻路
+ Mineflayer-like 技能抽象
+ Voyager-like 技能记忆
+ Ollama / llama.cpp 本地聊天
```

这样既能离线运行,又能逐步做强,不会一开始就被"完整通关 AI"这个超大目标拖死。

---

## 参考

- Fabric API: https://github.com/FabricMC/fabric-api
- Baritone(LGPL-3.0,引用需守许可证): https://github.com/cabaletta/baritone
- Mineflayer: https://github.com/prismarinejs/mineflayer
- mineflayer-collectblock: https://github.com/PrismarineJS/mineflayer-collectblock
- Voyager: https://github.com/minedojo/voyager
- Ollama: https://github.com/ollama/ollama
- llama.cpp: https://github.com/ggml-org/llama.cpp
