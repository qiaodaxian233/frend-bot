# frend · 项目交接文档(HANDOVER)

> 给接手这个项目的人(或新对话里的 AI 助手)。读完这份就能无缝接上,不用回头翻聊天记录。
> 仓库:https://github.com/qiaodaxian233/frend-bot　·　Minecraft **Fabric 1.21.1** · 纯 Java,无前置 mod(Fabric API 除外)。
>
> 🔧 **动手写代码 / 改配置 / 提交前,先读 `SKILL.md`**(本仓库根目录)——沙箱能做不能做的事、必须查证的版本敏感 API、交活前自查清单。姊妹仓库 qiaodaxian233/yongye 是同作者同工作流的 1.21.1 工程,**拿不准的 API 先去那边找已编译验证过的用法照抄**。

---

## ⭐ 开发守则(置顶 · 所有协作者与 AI 助手必读必守)

```
以瞎猜接口为耻,以认真查询为荣。
以模糊执行为耻,以寻求确认为荣。
以臆想业务为耻,以人类确认为荣。
以创造接口为耻,以复用现有为荣。
以跳过验证为耻,以主动测试为荣。
以破坏架构为耻,以遵循规范为荣。
以假装理解为耻,以诚实无知为荣。
以盲目修改为耻,以谨慎重构为荣。
```

> 本项目沙箱编译不了 Fabric/Mojang 依赖,所有改动由人类本地 IDEA + JDK21 编译验证。拿不准的接口/签名一律先查 yongye 或本仓库现有用法照抄;实在无法核实的,明确标注「待编译验证」,绝不假装确定。

---

## 0. 一分钟速览

- frend = **本地运行的 Minecraft 陪伴机器人**:类玩家 NPC,像朋友一样陪玩家生存/聊天/干活/打怪,完全离线。蓝图见 `docs/DESIGN.md`,一句话产品定义:*不是外挂,不是刷材料机器,而是会记住你、陪你冒险的本地 AI 朋友*。
- 核心架构原则(v0.4 起生效,现在就要守):**LLM 永不直接控制游戏**,只产出意图,执行走白名单技能 DSL。
- 当前进度:**里程碑 7 / v0.6(矿下安全)已落地,全部未经本地编译验证**。

## 0.5 状态行(最新在前,`· 上一里程碑` 分隔)

m7(v0.6 矿下安全):三层防护——寻路层 setPathfindingPenalty(LAVA/DAMAGE_FIRE=-1 禁区,DANGER_FIRE=16,一处设置全局生效)+挖掘层 MineTask#safeToMine(六邻岩浆不挖/头顶两格 FallingBlock 不挖,选块过滤+开挖前复查,一次任务解释一次)+照明层 tryPlaceTorch(方块光<7 且天空光<7 双条件防白天误判+canPlaceAt+2s 硬冷却+光照自然拉间距+念叨 5min 一次+没火把 30s 退避不抱怨);config v4→v5 新增 autoTorch/torchLightThreshold/mineSafetyEnabled;新增待编译验证:PathNodeType 枚举/getLightLevel(LightType,pos)/canPlaceAt/getFluidState+FluidTags.LAVA/FallingBlock;仍未沙箱编译。 · 上一里程碑

m6(v0.5 自主行动):entity/FrendAutonomy(mobTick 驱动,纯本地优先级规则=红线,LLM 不参与决策)+决策阶梯(包满≥70%→自主 DepositTask/有斧→ChopTree/有镐→MineTask.STONE/允许徒手兜底/没工具提一嘴)+触发条件 STAY 闲置 45s+防抽风(决策冷却 120s/开工<6s 秒结束→冷却×3 退避/FOLLOW 不离队只建议 10min 一次)+环境闲话(日出日落按游戏天去重/下雨状态沿/主人在 chatRadius 才说)+/frend auto on|off+关键词"自由活动/看着办"开"别自作主张/听我指挥"关+config v3→v4 六个新字段默认开;新增待编译验证:World#isRaining;仍未沙箱编译。 · 上一里程碑

m5(v0.4 长期记忆):entity/FrendMemory(相识时刻→天数换算/击杀/救主/砍挖计数/12 条大事记,随实体 NBT 持久化,死亡即消失=刻意设计)+埋点四处(CombatGoal 击杀死判+defendingOwner 救主标志、Chop/MineTask 计数、DepositTask≥8 组记大事)+出口三处(聊天关键词"还记得/战绩/认识多久"走规则、/frend memory 指令、status 尾缀战绩)+LLM persona 注入共同经历摘要(闲聊会提往事);击杀里程碑 10/50/100/500 一生一次;新增待编译验证:NbtElement.STRING_TYPE/NbtList.getString/getName().getString();仍未沙箱编译。 · 上一里程碑

m4.1(远端 m4 审查修复):并行开发冲突→弃本地版采远端版;修 4 处:onOwnerHurt 过滤玩家/同类(PVP 红线)、tickRetreatCooldown 补撤退计时递减(终身和平 bug)、攻击距离 12 格→3 格(间隔当距离用)、索敌白名单僵尸/骷髅/苦力怕+点火拉距;教训:动工前先 fetch 远端 HEAD。 · 上一里程碑

m3(v0.2 能干活):entity/task 任务框架(不落盘状态机,Mode.WORK,mobTick 驱动,切模式自然打断,重载退 STAY)+ChopTreeTask(BFS 整树/斧头可选)+MineTask(STONE/ORE,必须有镐,只挖露头,不自埋)+DepositTask(回家找箱倒货,工具食物自留)+工具耐久(findUsableTool 留量/damageTool 手动 setDamage 弃 ItemStack#damage)+自动进食(FOOD 组件,饱食度换算回血)+任务中捡拾;/frend work 5 子命令+聊天关键词(DEPOSIT 必须排在 HOME 前);新增待编译验证:setBlockBreakingInfo/FOOD 组件/areItemsAndComponentsEqual/ENTITY_GENERIC_EAT 是否 RegistryEntry/工具 ItemTags;仍未沙箱编译。 · 上一里程碑
m2(聊天大脑双后端):FrendLlmClient(OpenAI 兼容 /chat/completions,JDK HttpClient+gson 零新依赖,默认指本地 Ollama :11434/v1,全异步+server.execute 回主线程+响应清洗)+FrendChatHandler 重构(指令关键词永远走规则=红线;闲聊按 config.chatBackend 分流 rules/openai,LLM 失败/节流退模板;对话延续窗口 15s;夸奖/道别新关键词)+FrendEntity 聊天记忆环形队列/窗口计时/请求节流+FrendConfig v2 聊天大脑段;新增待编译验证:World#getTimeOfDay、ServerWorld#getServer;仍未沙箱编译。 · 上一里程碑
m1(v0.1 能出生/跟随/聊天):1.21.1 工程骨架+FrendEntity(主人绑定/三模式/家/27格背包/NBT)+跟随与回家 Goal+规则层聊天(关键词+模板+延迟回话)+像人细节(低血提醒/闲聊/受伤喊/遗言/被动回血/跑丢兜底传送)+Steve 皮肤玩家模型渲染【待编译验证】+/frend 指令树 8 个子命令;版本决策:弃 1.20.1 改 1.21.1(照抄 yongye 已验证 API);未在沙箱编译,待作者本地 build 回传报错。

## 1. 工程结构

```
src/main/java/com/frend/
├─ Frend.java                  主入口:配置 → 注册 → 系统
├─ FrendConfig.java            config/frend.json(gson,缺字段用默认值)
├─ registry/ModEntities.java   实体注册(照 yongye 模式)
├─ entity/
│  ├─ FrendEntity.java         ★ 核心:模式/主人/家/背包/NBT/说话/像人细节/任务驱动/自动吃/工具耐久
│  ├─ FrendFollowOwnerGoal.java 跟随(+跑丢兜底传送)
│  ├─ FrendGoHomeGoal.java     回家(+无进展放弃)
│  └─ task/                    v0.2 干活任务(不落盘)
│     ├─ FrendTask.java        基类:moveNear/breakTick(慢慢挖+进度动画)
│     ├─ ChopTreeTask.java     砍树(BFS 整树)
│     ├─ MineTask.java         挖石/挖煤铁(必须有镐,只挖露头)
│     └─ DepositTask.java      回家存箱子(工具食物自留)
├─ system/
│  ├─ FrendCommands.java       /frend 指令树(不要 OP)
│  ├─ FrendChatHandler.java    聊天:指令走规则(红线)+闲聊双后端(rules/openai)
│  ├─ FrendLlmClient.java      OpenAI 兼容接口客户端(Ollama/LM Studio/OpenAI 通用,全异步)
│  └─ FrendScheduler.java      延迟任务(说话不秒回)
└─ client/
   ├─ FrendClient.java         客户端入口
   └─ render/FrendRenderer.java 玩家模型+Steve 皮肤【待编译验证】
```

设计文档的模块划分(frend-core/body/perception/…)是**逻辑分层**,当前用单模块包结构承载,别为了对齐文档强行拆 Gradle 子项目。

## 2. 关键设计点(改代码前必知)

- **模式只在服务端**:`FrendEntity.Mode` 走 NBT 持久化,客户端渲染不依赖它,所以**没有 DataTracker**。以后要做客户端可见状态(比如头顶模式图标)再加 DataTracker,别顺手把现有逻辑搬进去。
- **说话统一走 `FrendEntity.say/sayDelayed`**:按 `chatRadius` 广播、带前缀、延迟走 `FrendScheduler`。新增台词一律带**冷却**,设计红线是"不刷屏"。
- **跟随不瞬移是设计原则**,`teleportDistance`(默认 48)只是防卡死保险丝且可关;别把它调小当常规手段。
- **背包用原版 9×3 界面**(`GenericContainerScreenHandler.createGeneric9x3`),零自定义 screen/网络包;v1.0 配置 GUI 之前保持这个零成本方案。
- **聊天关键词匹配是 contains**,先匹配 FOLLOW 再 COME("跟我来"别被"过来"截胡)——改关键词表注意顺序。
- 指令只作用于"附近 128 格内主人是你"的 frend;summon 上限按"附近 256 格已加载实体"计数,**跨维度数不到**,这是已知取舍(记忆系统 v0.4 再全局化)。

## 3. 踩过的坑

- (m1)沙箱编不了 Fabric → 所有无先例 API 标「待编译验证」,清单见 DEVLOG m1 末尾。
- 更多坑等首次本地编译后回填。yongye 的 SKILL.md §4 列了一批 1.21.1 版本敏感 API(GENERIC_ 前缀属性、Inventories 要 registryLookup、事件包路径),frend 同样适用。

## 4. 工作流

1. 沙箱重拉仓库(`git ls-remote` 核对远端 HEAD,别假设本地=最新)→ 读 HANDOVER 0.5 状态行 + SKILL.md。
2. 写代码 + 静态自检 → commit + push(作者给一次性 PAT;没 PAT 导 `.patch`)。
3. 作者本地 IDEA(JDK 21)`./gradlew build` + 进游戏实测 → 报错/问题回传 → 精确修复。
4. 每个里程碑更新:DEVLOG(新条目)、HANDOVER 0.5 状态行(往前追加)、README(能力表)。
