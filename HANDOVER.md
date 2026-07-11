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
- 当前进度:**里程碑 9 / v0.8(弓箭远程)已落地,全部未经本地编译验证**。

## 0.5 状态行(最新在前,`· 上一里程碑` 分隔)

m19(v0.18 灵魂与重逢,作者钦点四件:离线问候/催泪对话/自动学话/存档互通)⚠️设计反转在案:v0.4"死亡即消失"经作者明示升格为灵魂(死亡换档都带不走,死亡台词改"我们还会再见");FrendSoul=config/frend/souls/<uuid>.dat(NbtIo 压缩,记忆全量+名字+天数快照+LastSeenMillis;存盘=5min+死亡+解散+下线;召唤只灌白纸 isFresh;跨档天数坑=世界时间不可比→快照+rebaseTo/bonusDays 续算;多只共享一魂分魂留协作);重逢=JOIN/DISCONNECT 记时刻→上线算天数压待问候表→聊天半径内才说,分级 0/1-2/3-6/7-29/30+(一个月看哭标准),≥7 天记大事;纪念日 10/100/365 各一次;学话 phraseLearning(纯本地词频红线不涉 LLM,2~10 字短句 3 次成诵上限 6 句候选 16,闲聊 20% 蹦口头禅);人设补"不命令只帮忙";config v15→v16。 · 上一里程碑

m18(v0.17 LLM 意图解析,还 v0.4 架构账"LLM 只产出意图执行走白名单"):三道闸——规则关键词永远先行(命中不进 LLM)/白名单 19 意图+none(模型只输出一行 JSON {"intent","say"},白名单外一律当聊天)/执行同源(executeIntent 各分支与 handleCommand 调用完全相同,模型无任何直接触碰游戏通道);细节——Gson 正经 parse(首尾花括号截断防 fence/闲话,解析失败整段当聊天)/say 超长截断/status+memory 回真实数据不让模型编/执行成功用模型 say 代替罐头台词(有性格)/persona 拆双口径重载老调用零改动;config v14→v15 llmIntentEnabled(默认开仅 openai 生效,关掉退回纯聊天)。 · 上一里程碑

m17(v0.16 全智能自给自足,作者钦点终极方向"不给指令全智能"):新 CraftTask 虚拟合成(原版配比 1 木→4 板/2 板→4 棍/3 圆石+2 棍→石器/1 煤+1 棍→4 火把;简化在案:免工作台+板统一橡木;每 25tick 一步站桩挥手不瞬变,打断不回滚;镐>斧>剑,有圆石优先石器,木镐=自举钥匙)+决策阶梯 v2(0 天黑收敛 nightCaution 夜里不接新活每晚一句/1 包满存/1.5 缺工具自造+火把见底自搓/2 有斧砍有镐凿/2.5 白手起家徒手撸树自举;selfSufficient=false 完全退回 v0.5);闭环=徒手→木镐→圆石→石器→火把→存家零指令;已知取舍:升级只在报废后自然发生+铁器链(熔炉状态机)不做;聊天"做工具/搓火把"可手动;config v13→v14。 · 上一里程碑

m16(v0.15 帮你捡尸)+Baritone 源码研读#1(作者授意参考不搬运,github 在沙箱白名单直接拉 MovementHelper 研读):研读三收获——正上方液体永远回避(已补 miningDanger 漏的水)/虫蚀方块回避(白名单免费红利已然安全,记录不改码)/侧邻无支撑沙砾(评估不致命不抄,记录理由);m16——SalvageTask(你死→喊话→赶出事点→8 格掉落逐收→静默 4s 或 90s 兜底收工→转 FOLLOW 送货→4 格内 giveSalvageBack 塞你背包塞不下落脚边)+遗物袋独立仓(SimpleInventory45,NBT 同款已验证写法,与自有背包隔离=DepositTask 永不存走遗物,死亡解散一并散落,经验球刻意不收)+走不到出事点如实认怂;顺手修 mobTick 任务收尾无条件 STAY→尊重任务自改模式;config v12→v13 collectOwnerDrops。 · 上一里程碑

m15(v0.14 战斗进修+鱼骨矿道,作者点题 Wurst/Baritone):思路翻译不搬码(外挂架构不同+许可证),红线不动(不打玩家/像人:有起跳蓄力走位无瞬杀锁头);战斗四件——跳劈暴击 critHits(先跳一 tick 下落落刀,手动补 50% 伤+CRIT 粒子+玩家暴击音效,critPending stop 清零)/威胁索敌 threatTargeting(评分:点火苦力怕+500>打 owner+300>打我+200>残血加权>距离罚分,关掉退回就近)/间隙走位 strafeInCombat(冷却期 15% 侧移 25% 换边,近战落地才做)/箭提前量 bowLeadTarget(飞行时间×目标速度封顶 3 格);挖矿两件——有界追脉 veinChaseMax=12(自首:v0.13 注释吹不追脉但 scanForOres 早在无界追,教训=注释描述实际不是愿望)/鱼骨矿道 branchMining(到层主巷每 4 步左右各开 5 格分支,状态机 LEFT→回→RIGHT→回,分支遇险掉头不废主巷);config v11→v12。 · 上一里程碑

m14(v0.13 挖矿路径规划):作者提 automodpack→查证为整合包同步工具无关挖矿,实指 Baritone,借思路不抄码(架构不同+LGPL);新 TunnelTask 双模式——TUNNEL 平巷(开工朝向取整 1x2 直巷上限 tunnelMaxLength=48)/DEEP 下矿(楼梯法进1降1断面3高到 deepMineTargetY=-58 转平巷,断面自上而下);红线四连(任一触发整条道收工,隧道有方向绕不开):白名单防拆家(BASE_STONE 系+圆石泥土沙砾+全矿种,白名单外="像有人修的")/v0.6 避险提炼至基类 miningDanger(MineTask 委托零行为变化)/渗水收工/挖穿溶洞不搭桥停工叫人;见矿顺手掏(破块扫六邻入队,只掏露头不追脉);掘进预算独立不受 maxBlocksPerJob 约束;入口 /frend work tunnel|deep+聊天关键词(TUNNEL/DEEP 必须排 KEY_ORE 前,"挖矿道"含"挖矿");config v10→v11。 · 上一里程碑

m13(v0.12 路径规划,作者点题):开门关门(MobNavigation#setCanPathThroughDoors+NodeMaker#setCanOpenDoors 村民同款寻路+LongDoorInteractGoal(this,true) 优先级5 路过开走过带上,类名待验证)+游泳意愿(WATER 惩罚 8→0+setCanSwim,SwimGoal 兜底不淹)+卡死自救(mobTick 每 2s:导航中位移<0.5→跳一下→再卡 stop 停表让 Goal 重算+"我绕绕"60s 冷却;拉弓/挖矿 nav idle 不误判;48 格传送保险丝仍是终极兜底)+长途分段寻路 navigateSmart(FOLLOW_RANGE 钉死搜索范围→直达失败取 24 格中间点分段蹭,接入 GoHomeGoal+FrendTask#moveTo 全任务受益;≤24 格不硬分段交给自救);评估不做:爬梯子/搭路拆墙(拆错玩家建筑=灾难);config v9→v10 openDoors/stuckRescue。 · 上一里程碑

m12(v0.11 有来有往)🎉首次全绿 build:基于 4ddbce8 三轮 build 零报错,m1~m11 全部挂账 API 编译实证销账(含最高风险 FrendRenderer),残留【待编译验证】注释视为已验证顺手摘牌不扫仓;m12——分你吃的(你低血→朝你扔一份食物,ItemEntity 定向初速,最后一口也给"我扛得住",findFoodSlot 与 tryEat 同源重构,8 格内,config shareFoodWhenOwnerLow)+记你栽跟头的地方(AFTER_DEATH 玩家分支 128 格在场→坐标 FIFO3"维度|x|y|z"入 NBT+路过 16 格提醒 5min 冷却+刚死压 2min 不烦跑尸,config deathSpotWarn)+箭账清偿(v0.8 欠账:凶器 PersistentProjectile+射手 frend→recordKill/Rescue,与白刃收尾检测零冲突);AFTER_DEATH 三合一分发(你倒下/箭杀/你救我,分支互斥);config v8→v9。 · 上一里程碑

m11(v0.10 朋友不是仆人,作者钦点方向)+编译清账#2:清账——fabric-dimensions-v1 在 fabric-api 0.105 已删+TeleportTarget 实为新式六参(ServerWorld+PostDimensionTransition,javac 实证,"1.21.2 才改"记忆错误)→改原版 Entity#teleportTo+空 lambda(teleportTo 方法名待验证);m11——全部台词去主仆化(owner 标识符保留)+LLM 人设重写(平辈朋友/绝不叫主人/自称 getDisplayName)+双向记忆(ownerSaves 你救我:AFTER_DEATH 监听死怪 target 指向 frend 且凶手=owner,首次必谢后续 60s 冷却;gifts 送装计数;notes 记事"记住:xxx"≤60 字 FIFO8+LLM 注入)+起名(/frend name greedyString+聊天"你以后叫X",CustomName 白嫖,喊名字算 addressed)+解析顺序坑(起名/笔记置顶防工作关键词截胡,"记住什么"抢在裸"记住"前);逻辑风险预案:AFTER_DEATH 时点 mob.getTarget 可能已清→漏记则改 getAttacking/缓存。 · 上一里程碑

m10(v0.9 下界适应):交战规则修正(ZombifiedPiglin 是 ZombieEntity 子类会被白名单误伤→instanceof 豁免,中立不惹)+自卫反击 onSelfHurt(damage→注入目标,canStart 注入检查提到模式门槛前=任何模式还手;修 v0.3 起站桩挨打地雷;红线不打玩家/同类照旧)+跨维度跟随(getOwnerPlayerAnywhere 走 PlayerManager 全服查/每 2s 检查连续 2 次不在才追/FabricDimensions.teleport;关键坑:非玩家实体换维度=复制实体,teleport 返回值才是活的,喊话用返回值;lastDimension 进 NBT)+换维度风味话 60s 冷却+着火喊话 30s 冷却;评估不用动:火把双光照下界天然正确/家维度守卫 v0.2 已有/岩浆禁区 v0.6 全局/菌柄在 #logs 白吃;config v7→v8 selfDefense/crossDimensionFollow;新增待编译验证:FabricDimensions.teleport 泛型/TeleportTarget 四参构造(1.21.2+大改,报错查 DEVLOG m10)/Entity#getServer/PlayerManager#getPlayer(UUID);编译清账#1 已完成(ItemTags 包名/GENERIC_EAT/getRandom),等第二轮 build 报错。 · 上一里程碑

m9(v0.8 弓箭远程):距离换武器(>9 格有弓有箭换弓/<4 格换近战,滞回 4~9 防抖+20tick 换械冷却,对调不覆盖)+射击循环(setCurrentHand 拉弓可见/站桩蓄力 20tick/骷髅同款弹道 水平距离×0.2 抛物补偿/散布 6 固定/射后冷却 30tick/耗 ItemTags.ARROWS)+没箭喊一次换白刃+超 combatRange 或看不见先收弓;顺手修 autoEquipBestWeapon 覆盖式吃装备历史隐患(三处改对调+弓入不乱换白名单);已知欠账:箭异步击杀不进战绩(pending 对账修法排后);config v6→v7 rangedEnabled;新增待编译验证:createArrowProjectile 四参/setVelocity 五参/getBodyY/ENTITY_ARROW_SHOOT/copyWithCount;仍未沙箱编译。 · 上一里程碑

m8(v0.7 装备与外观):autoEquipArmorAndShield(盾→空副手就拿;甲→空槽就穿/比 getProtection 更硬才换/换下回包/包满落地;道谢 60s 冷却;age%40==20 与武器扫描错开)+渲染挂 ArmorFeatureRenderer 四参构造(ArmorEntityModel+PLAYER_INNER/OUTER_ARMOR,照抄原版 PlayerEntityRenderer,渲染仍是全仓最高风险区)+一件不昧(构造器 setEquipmentDropChance 六槽 2.0f 死亡必掉不折耐久+dropAllItems 追加剥装备=解散归还)+持久化白嫖 HandItems/ArmorItems 原版 NBT;config v5→v6 autoEquipArmor;新增待编译验证:getSlotType/getProtection/setEquipmentDropChance/dropStack/ArmorFeatureRenderer 构造;仍未沙箱编译。 · 上一里程碑

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
