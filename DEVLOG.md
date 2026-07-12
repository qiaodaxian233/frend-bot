# 开发记录(DEVLOG)

> 本项目从一份设计文档(docs/DESIGN.md)起步,逐里程碑落地。
> 工作流:代码在沙箱内编写 + 静态自检 → 用户在本地 IDEA(JDK 21)`./gradlew build` 验证 → 报错回传精确修复 → push 到 `main`。

---

## 里程碑 1 — v0.1:能出生、能跟随、能聊天

**关键决策:目标版本从设计文档的 Fabric 1.20.1/Java 17 调整为 Fabric 1.21.1/Java 21。**
理由:姊妹仓库 yongye 是同作者、同工作流、已大量编译验证过的 1.21.1 工程,frend 直接照抄其中已验证的 API 写法(实体注册、指令树、tick 事件、NBT/Inventories、requestTeleport、openHandledScreen 等),沙箱编不了 Fabric 的情况下这是风险最低的路线。要 1.20.1 分支后续再降。

落地内容:
- 从零搭 Fabric Loom 1.21.1 工程:Gradle 8.10.2 wrapper(拷自 yongye)、`fabric.mod.json`、空 mixin 配置占位、gson JSON 配置系统(`config/frend.json`)。
- `FrendEntity`(PathAwareEntity):主人 UUID 绑定、FOLLOW/STAY/GO_HOME 三模式、家坐标(含维度)、27 格 SimpleInventory(右键开原版 9×3 界面,零自定义 screen)、全量 NBT 持久化、死亡/解散掉落背包。
- 两个自定义 Goal:`FrendFollowOwnerGoal`(小跑跟随、不贴脸、跑丢 48 格才兜底传送到主人旁安全落点并交代一句)、`FrendGoHomeGoal`(寻路回家、到家转 STAY、15 秒无进展放弃并说明)。
- `FrendChatHandler`:fabric-message-api 监听公屏聊天,关键词 → 切模式/模板回话;设计文档"三层聊天"的第一层(规则层),v0.4 在其上接 Ollama。
- `FrendScheduler`:END_SERVER_TICK 延迟任务队列,回话带 8~30 tick 随机延迟(不秒回)。
- "像人"细节:主人低血提醒(30 秒冷却)、闲聊(4 分钟冷却 + 概率)、受伤喊疼(8 秒冷却)、死前遗言、被动回血保底(v0.2 学会吃东西前)。
- 渲染:`BipedEntityRenderer` + `PlayerEntityModel` + 原版 Steve 皮肤,零自绘贴图。**【待编译验证】**(yongye 无先例,风险点在渲染器注释里列全了)。
- `/frend` 指令树:summon(每人上限可配)/ follow / stay / come / home set / home go / status / dismiss。

**未在沙箱编译**(网络未放行 Fabric Maven),待作者本地 build 回传报错。重点盯:`FrendRenderer`(PlayerEntityModel 签名/EntityModelLayers.PLAYER/Steve 贴图路径)、`ServerMessageEvents.CHAT_MESSAGE` 签名、`Inventories.writeNbt/readNbt` 的 registryLookup 参数。

---

## 里程碑 2 — 聊天大脑升级:双后端(纯本地规则 + 可选 OpenAI 兼容接口)

作者方向确认:**"跟真人一样,但要完全本地化规则匹配模式;如果可以,支持接入 openai 接口"** → 双后端设计,规则永远是默认与兜底,LLM 是可选增强(设计文档里 v0.4 的"三层聊天"提前落了半层)。

落地内容:
- `FrendLlmClient`(新):OpenAI 兼容 `/chat/completions` 客户端。零第三方依赖(JDK 自带 `java.net.http` + MC 自带 gson)。本地 Ollama、LM Studio、云端 OpenAI 同协议,配置里换 baseUrl/model/key 即可,默认指向 `http://localhost:11434/v1`(Ollama,不出网)。全异步,回调经 `server.execute` 切回主线程才碰游戏状态;响应清洗(去 `<think>` 思维链/换行/引号、超长截断)。
- `FrendChatHandler` 重构:
  - **指令关键词(跟我来/停下/过来/回家/报告)永远走规则**——行为红线:模型输出永远不被解析成游戏操作。
  - 闲聊层按 `chatBackend` 分流:`rules`(默认)用模板池;`openai` 交给 LLM,失败/超时/节流自动退回模板,绝不刷屏。
  - "像人"新机关:**对话延续窗口**(frend 说完话 15 秒内,主人不喊名字也算在跟它聊)。
  - 规则模板扩池:新增 夸奖/道别 两类关键词与回话,fallback 措辞更自然。
- `FrendEntity`:聊天记忆环形队列(最近 N 条,不落盘,作 LLM 上下文;`say()` 顺手记 assistant 侧)、对话窗口计时、LLM 请求节流(单飞 + 最短间隔)。
- `FrendConfig` v2:新增"聊天大脑"配置段(backend/baseUrl/apiKey/model/超时/回复长度/历史条数/最短间隔/自定义人设/对话窗口)。

**【待编译验证】新增**:`World#getTimeOfDay()`(persona 里判断昼夜)、`ServerWorld#getServer()`(切回主线程用,标准 API 风险低)。

**未在沙箱编译**,同里程碑 1,待作者本地 build 回传。

---

## 里程碑 3 — v0.2 能干活:砍树 / 挖石 / 挖煤铁 / 回家存箱子 / 工具耐久 / 自动吃

架构:新增 `entity/task/` 任务框架(不落盘的瞬时状态机,mobTick 驱动)。任务只在新增的 **Mode.WORK** 下运行;主人一句"跟我来"切走模式即自然打断;存档重载后 WORK 退回 STAY。

- `FrendTask` 基类:`moveNear`(够不着先走过去 + 卡死计时)、`breakTick`("慢慢挖":朝方块看 + 周期挥手 + 破坏进度动画 + 按耗时破坏,不瞬爆——像人不像指令方块)。
- `ChopTreeTask`:搜最近原木 → 3×3×3 邻域 BFS 收整棵树(兼容斜枝,上限 48 块)→ 低→高逐块砍。**决策:够得着树根就整棵处理、高枝隔空砍,不真爬树**——不卡寻路、不留悬空半棵树,v0.2 的取舍。斧头可选(快一倍 + 耗耐久),没斧头徒手翻倍耗时。
- `MineTask`(STONE/ORE 双模):**必须有镐**,没镐开口要;只挖露头方块(至少一面挨空气),不挖自己脚下(不自埋)。ORE 走 `BlockTags.COAL_ORES/IRON_ORES`(深板岩变种免费兼容)。**不做避岩浆/火把,设计文档排在 v0.5。**
- `DepositTask`:走回家 → 家 ±4 格找箱子/陷阱箱/木桶 → 倒货。**工具(斧镐剑铲)和食物自己留着**——存完货不能变成手无寸铁饿肚子的憨憨。装不下如实汇报。
- 工具耐久:`findUsableTool`(剩余 ≤ toolReserveDurability 就不用,给主人留口气修)、`damageTool`(**不走 ItemStack#damage——1.21 签名多变风险高,手动 setDamage**,磨没了移除并喊一声)。
- 自动进食:血 < 阈值且背包有食物(FOOD 数据组件)就吃,回血量 = 食物饱食度;被动回血降级为无粮保底。
- 捡拾:**只在任务中**捡 2.5 格内落地物(不抢主人东西),掉落物 → 背包。
- 入口:`/frend work chop|stone|ore|deposit|stop` + 聊天关键词(砍树/挖石头/挖矿/存箱子/收工)。**"回家存箱子"含"回家",DEPOSIT 判定必须排在 HOME 前**(与 FOLLOW-before-COME 同款坑,SKILL 有记)。
- 每次任务上限 maxBlocksPerJob=32 块,到数收工汇报——防一句"挖矿"挖穿地图。

**【待编译验证】新增**:`World#setBlockBreakingInfo`(破坏进度动画)、`DataComponentTypes.FOOD`/`FoodComponent#nutrition()`、`ItemStack.areItemsAndComponentsEqual`、`SoundEvents.ENTITY_GENERIC_EAT` 是否 RegistryEntry(代码里带 `.value()`,报错就去掉)、`ItemTags.AXES/PICKAXES/SWORDS/SHOVELS`。两个模式 switch 已补 WORK 分支(否则 switch 不穷尽直接编译失败)。

---

## 里程碑 4 — v0.3 能打怪:战斗 / 支援 / 盾牌 / 撤退 / 自动装备

作者指令:**继续**(照路线图 v0.3)。

落地内容:

- **`FrendCombatGoal`**:优先级 2(高于跟随/回家,低于撤退和游泳)。  
  - FOLLOW + WORK 模式下自动扫描 `combatRange`(默认 12 格)内 `HostileEntity`,就近攻击;  
  - 支援主人:主人被攻击时 `Frend.java` 主类事件注入目标(`ServerLivingEntityEvents.ALLOW_DAMAGE`);  
  - 攻击间隔:有剑/斧 16 tick(0.8s),无武器 20 tick(1s),全异步 Goal 驱动不卡服;  
  - 战斗喊话:低概率随机(\"拿命来!\" 等 6 条),不刷屏;  
  - 副手盾牌:目标 5 格内自动举盾(`setCurrentHand(OFF_HAND)`),40 tick 自动放;出拳前先放盾;  
  - 保持距离:远了追,贴着了退一步(1.5 格临界);

- **`FrendRetreatGoal`**:优先级 1(最高)。  
  - 血量 ≤ `retreatBelowHealth`(默认 6 HP)触发;  
  - 向主人方向跑路 `retreatDurationSeconds`(默认 8 秒);  
  - 恢复时说\"好多了,再来!\";

- **`FrendEntity` 新增**:  
  - `combatGoal` 字段 + `onOwnerHurt(attacker)` 公共入口;  
  - `autoEquipBestWeapon()`:每 40 tick 扫背包,剑 > 斧,装到主手(主手已有武器不替换);  
  - `createFrendAttributes()` 新增 `frendAttackDamage`(默认 2.0,可配);  
  - `initGoals()` 重排:SwimGoal(0)→RetreatGoal(1)→CombatGoal(2)→FollowOwner(3)→GoHome(4)→LookAt(6)→LookAround(7);

- **`FrendConfig` 新增**:`combatEnabled/combatRange/autoEquipWeapon/shieldEnabled/retreatBelowHealth/retreatDurationSeconds/supportOwner/frendAttackDamage`;

- **`FrendChatHandler` 新增**:\"保护我/打怪/冲啊/攻击\" → 开战斗;\"别打了/住手/收剑\" → 关战斗;

- **`FrendCommands` 新增**:`/frend combat on|off`;

- **`Frend.java` 重构**:注册 `ServerLivingEntityEvents.ALLOW_DAMAGE` 监听主人受伤 → 通知 frend;

**【待编译验证】新增**:
- `ServerLivingEntityEvents.ALLOW_DAMAGE` 签名(Fabric entity-events-v1,yongye 里已用过,风险低);
- `frend.isBlocking()` / `frend.clearActiveItem()` / `frend.setCurrentHand(Hand)` — PathAwareEntity 继承自 LivingEntity,均为 1.21 稳定 API;
- `frend.tryAttack(target)` — MobEntity 标准 API;

旧待验证项(v0.1–v0.2)不变,一起本地 build 时再确认。

---

## 里程碑 4.1 — 远端 m4 代码审查修复(4 处)

背景:m4 出现并行开发——本地写了一版 v0.3,推送时发现远端已有另一版 m4(另一会话产出)。**弃用本地版、采用远端已发布版**(不覆盖已发布历史),审查后修了 4 处:

1. **红线漏洞(必修)**:`onOwnerHurt` 支援链路没过滤攻击者类型——主人被玩家打,frend 会打玩家(PVP 工具化)。补:`attacker instanceof PlayerEntity / FrendEntity` 直接忽略。
2. **"终身和平"bug**:`FrendCombatGoal.retreatTicks` 置位后无任何递减路径(Goal 不激活就不 tick),撤退一次后 `isInRetreating()` 永真、再也不战斗。补 `tickRetreatCooldown()`,由 `FrendEntity#mobTick` 每 tick 驱动。
3. **攻击距离公式错**:`dist <= attackInterval * 0.6 + 2.5` 把攻击间隔(tick)当距离(格)用,≈12 格隔空打人。改为近战 3 格。
4. **索敌白名单 + 苦力怕**:原来 HostileEntity 全打(会主动招惹末影人/女巫等),且贴脸打苦力怕必炸。补白名单(僵尸系/骷髅系/苦力怕,按设计文档)+ 点火拉距 6 格(【待编译验证】CreeperEntity#getFuseSpeed)。

流程教训已记 SKILL:**动工前先 fetch 核对远端 HEAD**,本地 ≠ 最新。

---

## 里程碑 5 / v0.4 — 长期记忆:frend 记得你们的故事

**目标**:「像真人」的下一块拼图——真人朋友记得共同经历。frend 现在有一份随 NBT 持久化的记忆档案,聊天和汇报都会自然带出来。

**新文件**:`entity/FrendMemory.java`——三层结构:
1. **计数器**:相识时刻(world time,`setOwner` 首次调用时落笔)→ 换算「一起冒险第 X 天」;击杀数;救主次数;砍木/挖矿方块数。
2. **大事记**:最近 12 条带天数戳的事件(旧的挤掉)。会记:首杀、击杀里程碑(10/50/100/500,一生一次)、每次救主、大额入库(≥8 组)。
3. **输出**:`recapLine`(口头回忆)、`llmSummary`(LLM 人设注入,省 token 只带 3 条近事)、`statusBrief`(状态汇报尾巴)。

**埋点**(全部一行式,不侵入原逻辑):
- `FrendCombatGoal.tick`:`tryAttack` 后目标死了 → `recordKill`;若目标来自「支援主人」注入(新 `defendingOwner` 标志,`canStart` 置位、`stop` 复位)→ `recordRescue`,首次救主有专属感慨。
- `ChopTreeTask` / `MineTask`:计数同步进记忆。
- `DepositTask`:入库 ≥8 组记一笔「满载而归」。

**出口**:
- 聊天关键词(规则,红线不变):「还记得 / 记得吗 / 认识多久 / 多少天 / 战绩 / 杀了多少 / 回忆」→ 口头回忆。
- `/frend memory` 指令,同款输出。
- `/frend status` 末尾追加战绩短句(空战绩不显示)。
- LLM 模式:persona 注入共同经历摘要——闲聊时它会自己提「上次从骷髅手里救你那回」,这才叫老朋友。

**设计取舍**:记忆存实体 NBT、frend 死了记忆一起消失——刻意的。它不是数据库,是"这一个伙伴"的一生;想要不朽,以后做「墓碑/传承」再说。

**【待编译验证】新增**:`NbtElement.STRING_TYPE` 常量 + `NbtList.getString(int)`(Yarn 1.21.1 标准 NBT API,风险很低)、`Entity#getName().getString()`(拿怪物显示名,风险低)。

---

## 里程碑 6 / v0.5 — 自主行动:不等命令,自己做该做的事

**目标**:主人提的核心诉求——「不用我输入命令,就自己做该做的事」。frend 现在有一颗规则驱动的决策脑,待命时自己找活干。

**新文件**:`entity/FrendAutonomy.java`,由 `mobTick` 每 tick 驱动。**红线不变**:决策 100% 本地优先级规则(不是 LLM);每次自主开工先打招呼;"收工/跟我来"随时打断(复用既有任务打断链路)。

**决策阶梯**(STAY 待命闲置 `autonomyIdleSeconds`(默认 45s)后触发,命中即止):
1. 背包非空格 ≥ `autonomyDepositAtFullness`(默认 70%)且家在本维度 → 自己回家存箱子;
2. 有斧头 → 附近砍树;
3. 有镐子 → 附近凿石头;
4. 允许徒手(`autonomyRequireTool=false`,默认不允许)→ 徒手砍树兜底;
5. 全落空 → 提一嘴"给我把工具"。

**防抽风三件套**:每次决策后 `autonomyCooldownSeconds`(默认 120s)冷却;自主任务开工 <6s 就结束(附近没活干)→ 冷却自动 ×3 退避;FOLLOW 模式绝不擅自离队,包满了只口头建议(10 分钟一次)。

**环境闲话**(`autonomyChatter`,可关):日出/日落各一天一次、开始下雨一次,主人不在 chatRadius 内不说(不对空气讲话)。

**开关**:`/frend auto on|off`;聊天说「自由活动 / 看着办 / 别闲着」开、「别自作主张 / 听我指挥」关。默认**开启**——这是产品主张:它就该是个不用管的伙伴。

**【待编译验证】新增**:`World#isRaining()`(标准 API,风险极低)。配置版本 v3 → v4(gson 缺字段走默认值,老配置无痛升级)。

---

## 里程碑 7 / v0.6 — 矿下安全:生存本能三件套

**背景**:MineTask 注释里自己立的 flag——「避岩浆/火把排到 v0.5」,版本顺延兑现。frend 会自己下矿干活了(v0.5 自主行动),没有生存本能等于自杀式打工。

**三层防护**(由外到内):
1. **寻路层**(FrendEntity 构造器):`setPathfindingPenalty` 把 LAVA/DAMAGE_FIRE 设为 -1(禁区)、DANGER_FIRE 设 16(重罚)——**任何**移动(跟随/回家/干活/战斗走位)都自动绕开岩浆和火,一处设置全局生效。
2. **挖掘层**(MineTask#safeToMine):目标六邻贴岩浆 → 不挖(挖穿被浇);头顶两格内是 FallingBlock(沙/沙砾)→ 不挖(被砸)。选块时过滤 + 每次开挖前复查(邻块被挖开后环境会变)。一次任务最多解释一次("那块贴着岩浆,我不碰"),之后沉默照跳。可配 `mineSafetyEnabled` 关闭(不推荐)。
3. **照明层**(FrendEntity#tryPlaceTorch):方块光 < 阈值 **且** 天空光 < 阈值(双条件 = 证明在洞里,挡住"白天野外方块光本来就是 0"的误判)+ 脚下空气 + 火把能站住 + 背包有火把 → 插一根。间距靠光照自然拉开 + 2 秒硬冷却;念叨 5 分钟一次;没火把不抱怨(30 秒退避)。

**配置 v4 → v5**:`autoTorch=true` / `torchLightThreshold=7` / `mineSafetyEnabled=true`。

**【待编译验证】新增**:`PathNodeType.LAVA/DAMAGE_FIRE/DANGER_FIRE` 枚举名、`World#getLightLevel(LightType, BlockPos)`、`BlockState#canPlaceAt`、`World#getFluidState` + `FluidTags.LAVA`、`FallingBlock` instanceof——全是原版稳定 API,风险低但签名细节需过一遍编译。

---

## 里程碑 8 / v0.7 — 装备与外观:穿上盔甲像个正经冒险家

**目标**:视觉与生存双升级——扔进背包的盔甲和盾牌它自己穿戴,而且**看得见**。

**自动穿戴**(`FrendEntity#autoEquipArmorAndShield`,每 40 tick 与武器扫描错开 20 tick):
- 盾 → 副手空着就拿(盾无优劣不换);
- 甲 → 槽位空着就穿;已穿则比 `ArmorItem#getProtection()` 护甲值,新的更硬才换,换下的放回背包(包满落地兜底);
- 穿上任何东西道个谢,60s 冷却。配置 `autoEquipArmor`(v6,默认开)。

**装备渲染**(`FrendRenderer`):BipedEntityRenderer 只自带头部/手持物/鞘翅 feature,盔甲层要自己挂——照抄原版 PlayerEntityRenderer:`ArmorFeatureRenderer` 四参构造(含 BakedModelManager,1.20.2+ 盔甲纹饰用)+ `ArmorEntityModel` + `PLAYER_INNER/OUTER_ARMOR` 模型层。

**一件不昧**(装备都是主人给的):
- 死亡:构造器里六个经典槽位 `setEquipmentDropChance(slot, 2.0f)`——必掉且不折耐久(刻意不碰 1.20.5+ 新增 BODY 槽,frend 用不上);
- 解散:`dropAllItems` 追加剥装备落地。

**持久化白嫖**:HandItems/ArmorItems 是 MobEntity 原版 NBT,equipStack 上去的装备自动随存档走,零新代码。

**【待编译验证】新增**:`ArmorItem#getSlotType()`(备选 `getType().getEquipmentSlot()`)、`ArmorItem#getProtection()`、`MobEntity#setEquipmentDropChance`、`Entity#dropStack`、渲染侧 `ArmorFeatureRenderer` 四参构造 + `ArmorEntityModel` + `PLAYER_INNER/OUTER_ARMOR`。渲染仍是全仓风险最高区(无 yongye 先例),报错优先核对 FrendRenderer 注释里列的点。

---

## 里程碑 9 / v0.8 — 弓箭远程:远了拉弓,近了拔剑

**目标**:像真人一样按距离选武器。v0.3 埋的 `hasRangedWeapon()` 钩子(当时只影响保持距离)正式实装。

**距离换武器**(带滞回防抖,20 tick 换械冷却):目标远于 9 格且包里有弓有箭 → 换弓("有点远,吃我一箭!");近于 4 格且包里有剑/斧 → 换回近战。滞回区间(4~9)不换,防止在临界距离抽风来回换。换械一律**对调**(原主手物进包位),不覆盖不吃装备。

**射击循环**:拉弓(`setCurrentHand`,客户端可见拉弓动作)→ 站桩蓄力 20 tick(像玩家蓄满力)→ 放箭 → 收弓冷却 30 tick。弹道照抄原版骷髅 `AbstractSkeletonEntity#shootAt`:抛物线补偿 = 水平距离 × 0.2,固定小散布(不按难度放水)。射一支耗一支(ItemTags.ARROWS,药水箭光灵箭都能用)。目标超 combatRange 或看不见 → 先收弓让移动逻辑贴近;弓盾不同时用;拉弓被打断(stop)蓄力清零。

**没箭了**:喊一声"没箭了,上白刃!"(一次,摸到箭复位),自动换回剑斧。

**顺手修的自引入 bug**:`autoEquipBestWeapon` 原来是"覆盖式"装武器(主手原物直接消失)——v0.8 弓会被剑覆盖吃掉,连带修好历史隐患(主手火把/镐被覆盖同理)。三处全改对调,并把"主手是弓"加入不乱换白名单。

**已知欠账(先写完后修,主人钦点)**:箭是异步击杀——现有记忆埋点只认 tryAttack 白刃收尾,射死的怪暂不进战绩/救主统计。修法预案:记 pending 箭靶,目标死时对账;排后。

**【待编译验证】新增**:`ProjectileUtil.createArrowProjectile` 四参签名(1.21 带武器参;报错退三参)、`PersistentProjectileEntity#setVelocity` 五参、`Entity#getBodyY`、`SoundEvents.ENTITY_ARROW_SHOOT`(可能要 .value())、`ItemStack#copyWithCount`。

---

## 编译清账 #1 — 首次真实 build,7 错全修(2026-07-11)

主人首次本地 `./gradlew build`(Loom 1.7.4 / JDK 21),9 个版本攒的账开始清。7 个错 3 类,全在历年【待编译验证】清单内,无一意外:

1. **ItemTags 包名**(5 处,FrendEntity):`net.minecraft.item.ItemTags` 不存在 → 正确包是 `net.minecraft.registry.tag.ItemTags`。任务类(Chop/Mine/Deposit/Autonomy)当初 import 就写对了所以没炸,只有 FrendEntity 里全限定名写错。教训:**全限定名要和已验证的 import 对齐,别凭记忆重写包路径**。
2. **吃饭音效**(1 处):`SoundEvents.ENTITY_GENERIC_EAT` 在 1.21.1 是纯 `SoundEvent`,不带 `.value()` → 去掉。同时反向确认:v0.8 射箭的 `ENTITY_ARROW_SHOOT` 不带 .value() 的写法没报错 = 正确,该项销账。
3. **protected 字段**(2 处,FrendCombatGoal):`Entity#random` 是 protected,Goal 类外部访问不到 → 改公开的 `frend.getRandom()`。

**已销账**:ItemTags 包名(v0.1 起挂账)、GENERIC_EAT/ARROW_SHOOT 音效(v0.2/v0.8)、random 访问。
**注意**:javac 一轮只报它解析到的错——这 7 个修完**必须再跑一次 build**,后面大概率还有下一批(渲染器 FrendRenderer 是全仓风险最高文件,一次都还没被编到报错,不代表它对)。

---

## 里程碑 10 / v0.9 — 下界适应:交战规则 + 自卫反击 + 跨维度跟随

**目标**:带它下下界不添乱、不送死、不走丢。

**交战规则修正**(下界地雷):僵尸猪灵在代码里是 `ZombieEntity` 子类 → v0.3 的"僵尸系主动清怪"白名单会误伤这只**中立生物**,打一只全族暴走。豁免:`instanceof ZombifiedPiglinEntity` 直接排除主动攻击;它先动手走自卫路径。猪灵(Piglin,穿金甲那种)本来就不在白名单里,不用动。

**自卫反击**(v0.3~v0.8 一直存在的地雷,这次顺路发现顺路修——不算违反"先写完",这是 v0.9 主题的一部分):以前 frend 被白名单外的怪打(疣猪兽/烈焰人/岩浆怪/被惹怒的猪灵群),只会喊疼不还手,站桩挨打到死。现在 `FrendEntity#damage` → `combatGoal.onSelfHurt(attacker)` 注入目标;`canStart` 里注入检查**提到模式门槛之前**——自卫在任何模式生效(STAY 站桩、GO_HOME 走路上被拱也还手)。红线原样:被玩家打绝不还手、不打同类。配置 `selfDefense`(默认开)。

**跨维度跟随**:`getOwnerPlayer` 走 `world.getPlayerByUuid`,主人进下界后本维度查无此人 → frend 原地发呆到天荒地老。新增 `getOwnerPlayerAnywhere()`(server.getPlayerManager 全服查),FOLLOW 模式每 2s 检查,**连续两次不在才追**(主人进门折返不白跑),经 `FabricDimensions.teleport` 过去,落地喊"等等我,这就来!"。配置 `crossDimensionFollow`(默认开)。
- **关键坑**:非玩家实体换维度是**复制实体**(旧的销毁、新维度重建,走完整 NBT 读写——背包/记忆/装备自动带走)。teleport 的**返回值**才是活着的那只,传送后不能再碰 this,喊话用返回值喊。
- `lastDimension` 进 NBT(`LastDim`),不然每次过门风味话状态清零。

**风味**:换维度说一句(下界/末地/回主世界各一句,60s 冷却,刚生成时静默不喊);着火喊一声(30s 冷却)。

**没动的**(评估过不用动):火把双光照条件在下界天然成立(天空光恒 0,方块光主导),行为正确;家维度守卫 v0.2 就有;岩浆/火寻路禁区 v0.6 全局生效;下界绯红/诡异菌柄在 #logs 里,砍树任务白吃这个福利。

**【待编译验证】新增**:`FabricDimensions.teleport` 泛型签名(fabric-dimensions-v1,全量 fabric-api 自带)、`TeleportTarget(Vec3d, Vec3d, float, float)` 1.21.1 构造(1.21.2+ 该 API 大改为 Entity#teleportTo,如报错优先查这里)、`Entity#getServer`、`PlayerManager#getPlayer(UUID)`、`ZombifiedPiglinEntity` 类路径(net.minecraft.entity.mob)。

---

## 编译清账 #2 — v0.9 跨维度两个错(2026-07-11)

第二轮 build 报 2 错,都在跨维度传送:

1. **fabric-dimensions-v1 已不存在**:该模块在 fabric-api 0.105.0+1.21.1 里已被移除(不是没引依赖,是上游删了)。
2. **TeleportTarget 构造签名**:javac 把可用构造器直接吐出来了——这套 1.21.1 yarn 里就是**新式签名**
   `TeleportTarget(ServerWorld, Vec3d pos, Vec3d velocity, float yaw, float pitch, PostDimensionTransition)`
   (之前按"1.21.2 才大改"的记忆写了旧式四参,记忆错了,**以编译器输出为准**)。

修法:弃 Fabric API,走原版 `Entity#teleportTo(new TeleportTarget(world, pos, Vec3d.ZERO, yaw, pitch, entity -> {}))`——
PostDimensionTransition 是函数式接口,空 lambda 零副作用,也避免赌 NO_OP 常量名。
**剩一个待验证**:`Entity#teleportTo(TeleportTarget)` 方法名本身(若报错找 moveToWorld/changeDimension 系)。
反向销账:NbtElement.STRING_TYPE(两轮编译均未报错,注释已摘牌)。

---

## 里程碑 11 / v0.10 — 朋友,不是仆人(作者钦点方向)

**作者原话**:"我要做的不是主人和仆人,是朋友。记忆系统。"这不是加功能,是掰正精神内核——
以往的记忆系统单向记"我为你做了什么",朋友之间的账是**双向**的。

**台词去主仆化**(全部玩家可见文本):"别想伤我的主人!"→"别想伤我朋友!";"主人,我包快满了"→"哎,我包快满了";
"我的主人"兜底称呼→"我朋友";击杀百只感慨去"主人"。代码标识符 owner 保留(纯内部,改了徒增 churn),注释按需。

**LLM 人设重写**:明写"你们是一起冒险的朋友——平辈相处,不是仆人,绝不叫对方主人;可以打趣、可以有小脾气、
可以不同意,但重感情、靠得住"。自称改用 getDisplayName(起过名就自称新名字)。

**双向记忆**(FrendMemory 三个新维度,全走既有 NBT 链路):
- **你救我**(ownerSaves):AFTER_DEATH 监听——死的怪 target 正指向某 frend 且凶手是它那位朋友 → 记账+道谢
  (第一次必说"这份情我记一辈子",之后 60s 冷却;记忆永远记,嘴上不刷屏);
- **你送我的**(gifts):穿装备道谢处顺手计数;
- **你让我记的事**(notes):聊天说"记住:xxx"存笔记(≤60 字,FIFO 8 条),"你记得什么"复述,
  **LLM 人设注入笔记**——闲聊时它会自然提起你特意交代的事,这才是老朋友。

**起名字**:`/frend name <名字>` 或聊天"你以后叫XX/你就叫XX/给你起名XX"。走原版 CustomName(头顶显示、
say 前缀自动变、NBT 白嫖持久化),记忆记一笔大事,之后**喊它的名字也算在叫它**(对话触发)。

**解析顺序的坑**:起名/笔记必须在 handleCommand **最前面**判——"给你起名砍树侠"/"记住:明天去挖矿"里
夹带工作关键词,放后面就被砍树/挖矿截胡开工了;"记住什么"(问话)要抢在裸"记住"(记事)之前。
内容从 raw 原文取(保留大小写),清洗头尾口语标点。

**【待编译验证】新增**:Entity#teleportTo 方法名、StringArgumentType.greedyString、Entity#hasCustomName/
setCustomNameVisible、MobEntity#getTarget 在 AFTER_DEATH 时点是否仍持值(逻辑风险非编译风险:怪死时 target 可能已清,救我判定漏记的话改用 getAttacking 或 attacker 缓存,预案记此)。

---

## 🎉 首次全绿 build(2026-07-11,基于 4ddbce8)

第三轮 `./gradlew build` **零报错通过**。意义:m1~m11 十一个里程碑攒的所有【待编译验证】项
(含全仓最高风险的 FrendRenderer 玩家模型+盔甲层、跨维度 teleportTo、弓箭弹道、NBT 全家桶)
一次性全部销账——**自此所有历史挂账的 API 用法均为编译器实证**。文件里残留的旧【待编译验证】
注释视为已验证,后续顺手摘牌即可,不做全仓扫除(纯 churn)。
三轮清账共 9 错,全部命中挂账清单、零意外——「不确定就挂牌」的工作流被证明是对的。

---

## 里程碑 12 / v0.11 — 有来有往:朋友会为你做的三件小事

延续 v0.10 方向(朋友,不是仆人),这次是行动不是台词:

**1. 分你吃的**:你血量见底,它不再只是嘴上喊——包里有吃的就朝你脚边**扔一份**(一份,不是一组),
"接着!先垫口吃的,别硬扛。"扔完发现那是它最后的口粮,会说"最后一口了,你吃,我扛得住"——
刻意不留私:朋友有难,最后一口也分你(代价是它自己的自动进食断粮,值)。
实现:ItemEntity 朝你的方向给初速度(单位向量×0.4+抬 0.2),复用 findFoodSlot(tryEat 同源重构)。
配置 shareFoodWhenOwnerLow(v9,默认开),距离 8 格内才扔(远了扔不准)。

**2. 记住你栽跟头的地方**:你死了(AFTER_DEATH 玩家分支,128 格内它在场),它记下坐标
(FIFO 3 处,编码"维度|x|y|z"进 NBT)+大事记+喊"你先回来,东西我帮你看着!";
之后你走到任一死亡点 16 格内(同维度),它提醒"小心点……你上回就是在这附近栽的"
(5min 冷却;刚死完压 2min 冷却,跑尸时不在原地念叨)。配置 deathSpotWarn(v9,默认开)。

**3. 箭账清偿**(v0.8 欠账):射死的怪进战绩/救主。走 AFTER_DEATH——凶器是 PersistentProjectile
且 getAttacker 是 frend → recordKill(+死怪 target 是它朋友则 recordRescue)。
**与白刃零冲突**:白刃击杀凶器是 frend 本体不是箭,此分支不触发;白刃入账仍走战斗 Goal 收尾检测。

**监听三合一**:Frend.java 的 AFTER_DEATH 一个注册点分发三路(你倒下/箭杀入账/你救我),
分支互斥(玩家分支 return;箭杀后凶手是 frend,"你救我"的玩家判定天然不命中)。

**【待编译验证】新增**:DamageSource#getSource(直接实体,区别于 getAttacker)、
ItemEntity(World,x,y,z,ItemStack) 构造、Entity#getEyeY、Vec3d#multiply/add——均为常见 API,风险低。

---

## 里程碑 13 / v0.12 — 路径规划:像人一样走路(作者点题)

四块拼图,解决"跟不上/进不去/游不动/走不远":

**1. 开门关门**(最伤"朋友感"的就是跟你回家却卡在门口):
- 寻路层:`MobNavigation#setCanPathThroughDoors(true)` + `getNodeMaker().setCanOpenDoors(true)`(村民同款,关着的木门算可走);
- 行为层:`LongDoorInteractGoal(this, true)` 优先级 5——路过木门伸手开,走过去**随手带上**(第二参 true = 延迟关门,卫道士突袭时用的就是这个 Goal)。配置 openDoors(v10,默认开)。
- 【待编译验证】LongDoorInteractGoal 类名(Yarn;报错找 DoorInteractGoal 子类)。

**2. 游泳意愿**:WATER 寻路惩罚默认 8——它会把河当障碍绕半个地图。清零 + `setCanSwim(true)`,
该游就游;已有的 SwimGoal(优先级 0)保证浮起来不淹死。铁门/活板门不碰(打不开的本来就不算路)。

**3. 卡死自救**(mobTick 每 2s 查一次,配置 stuckRescue):导航进行中却没挪窝(位移 <0.5 格)→
第一次**跳一下**(多半是一格台阶/栅栏);还卡 → `getNavigation().stop()` 停表,Goal 下 tick 换条路重算,
说一句"这路不好走……我绕绕"(60s 冷却)。拉弓站桩/挖矿敲方块时导航是 idle 的,不会误判。
最终兜底不变:跟随的 48 格传送保险丝。

**4. 长途分段寻路**(navigateSmart):原版寻路搜索范围被 FOLLOW_RANGE(48)钉死,"回家"在几百格外时
一次算不出完整路径直接摆烂——v0.2 起"这条路我走不过去"多半是这个。先试直达,不行就朝目标方向取
**24 格中间点**走一段;回家 Goal 每秒重发、任务 moveTo 反复调用,一段一段蹭过去。近距(≤24)找不到路
不硬分段——那是地形问题,交给卡死自救。接入两处:FrendGoHomeGoal、FrendTask#moveTo(所有任务共用,
存箱子回家那条长腿受益最大)。

**评估过不做**:爬梯子(要仿蜘蛛改 isClimbing,收益小改动深);搭路/拆墙过障(Baritone 路线,
拆错一块玩家建筑就是灾难,红线宁缺勿滥)。

**【待编译验证】新增**:LongDoorInteractGoal、EntityNavigation#getNodeMaker/PathNodeMaker#setCanOpenDoors、
MobNavigation#setCanPathThroughDoors、EntityNavigation#setCanSwim、MobEntity#getJumpControl#setActive——
除 Goal 类名外均为常见导航 API。

---

## 里程碑 14 / v0.13 — 挖矿路径规划:隧道掘进与楼梯下矿

**作者问**:"挖矿是不是可以参考 automodpack?"——查证:AutoModpack 是**整合包同步**工具(服务器给
玩家自动下发 mod),和挖矿寻路无关;作者想的应是 **Baritone**(自动寻路挖矿 bot)。代码不抄
(客户端操控玩家 vs 服务端实体,架构不同;且 LGPL),**思路借三条**:奔目标矿层挖安全楼梯、
直巷推进、途中见矿顺手掏。

**新任务 TunnelTask**(两模式一个类):
- **TUNNEL 平巷**:沿开工朝向(取整四方向,之后不歪——直巷的意义就是直)挖 1x2,上限 tunnelMaxLength(48);
- **DEEP 下矿**:标准楼梯法(每步进 1 降 1,断面 3 高防碰头)挖到 deepMineTargetY(-58,1.18+ 钻石层),
  到层自动转平巷,预算共享。断面自上而下挖(沙砾先露先处理)。

**红线与安全**(任一不满足整条道就地收工——隧道有方向,没法像散挖那样跳块绕):
- **白名单防拆家**(最重要):只挖 BASE_STONE_OVERWORLD/NETHER + 圆石/泥土/沙砾 + 全矿种。
  木板玻璃黑曜石一概"像有人修的,这条道到这儿"——宁可停工不碰玩家建筑;
- v0.6 避险(贴岩浆/悬沙)——判定逻辑**提炼到 FrendTask 基类 miningDanger**(返回原因字符串,
  MineTask 改委托,零行为变化);
- **渗水检测**:断面邻块有水 → "再挖就灌"收工;
- **挖穿溶洞**:落脚点悬空 → 不搭桥(红线:掘进不放置方块),"我不跳,你来看看?"停工。

**见矿顺手掏**:每破一块扫六邻,新露头矿(煤铁铜金红青钻绿+石英+远古残骸)入队优先掏,
**只掏露头不追脉**——追脉把直巷挖成蚁穴。火把靠实体层 autoTorch 白嫖;镐门槛/耐久同 MineTask。
掘进步数用独立预算 tunnelMaxLength,不受 maxBlocksPerJob(32)约束(一条 48 格平巷本身就 96 块)。

**入口**:`/frend work tunnel|deep`;聊天"挖隧道/打矿道/掘进"、"下矿/挖深矿/挖钻石"。
关键词坑:"挖矿道"含"挖矿",TUNNEL/DEEP 必须排在 KEY_ORE 之前匹配。

**【待编译验证】新增**:BlockTags.BASE_STONE_OVERWORLD/BASE_STONE_NETHER、Entity#getBlockY、
Entity#getHorizontalFacing、Blocks.ANCIENT_DEBRIS/NETHER_QUARTZ_ORE——均常见,风险低。

---

## 里程碑 15 / v0.14 — 战斗进修 + 鱼骨矿道(Wurst / Baritone 思路,作者点题)

**方法论同 m14**:两个都是客户端外挂/机器人,代码不搬(架构不同+许可证),思路翻译成服务端实体
行为,红线不动摇——不打玩家、动作像人(有起跳有蓄力有走位,没有瞬杀没有锁头)。

**战斗四件(FrendCombatGoal,各带配置开关 v12)**:
1. **跳劈暴击** critHits:落地状态先起跳(这 tick 只跳),下一 tick 下落中出刀——玩家同款时序。
   tryAttack 不走玩家暴击公式,手动补 50% 攻击力伤害 + CRIT 粒子 + 玩家暴击音效。critPending
   状态在 stop() 清零防残留;起跳后目标跑了则落地normally出普通刀,不白跳第二次。
2. **威胁优先级索敌** threatTargeting:评分制取代"打最近的"——点着的苦力怕 +500(保命优先)>
   正在打我朋友的 +300 > 打我的 +200 > 残血加权(斩杀收割)> 距离罚分。关掉退回就近。
3. **出手间隙走位** strafeInCombat:攻击冷却期间 15% 概率向侧面挪一步半,25% 概率换边——
   不站桩换刀,也不绕成钟摆。只在近战、落地时做。
4. **射箭提前量** bowLeadTarget:按飞行时间(水平距离 / 1.6 格每tick)预判目标水平位移,封顶 3 格
   ——骷髅不会,神射手会。抛物线补偿照旧。

**挖矿两件(TunnelTask)**:
5. **有界追脉** veinChaseMax(12):**先自首**——v0.13 注释吹"只掏露头不追脉",但代码里矿被挖掉后
   scanForOres 扫它六邻,矿邻矿就一直入队,**其实早在无界追脉**。这次封顶:一条脉连挖 12 块收手
   ("这条脉够肥,先掏到这儿"),队列清空计数归零。教训:**注释要描述代码实际做的事,不是想做的事**。
6. **鱼骨矿道** branchMining:下矿到层后,主巷每 branchInterval(4) 步向左右各开一条 branchLength(5)
   格 1x2 短分支(经典分支采矿覆盖率)。小状态机 LEFT→回主巷→RIGHT→回主巷→继续主巷;
   **分支是消耗品**:遇险/白名单外/渗水/悬空一律掉头回主巷,不像主巷那样整条收工。

**【待编译验证】新增**:LivingEntity#getDamageSources().mobAttack、ServerWorld#spawnParticles
(ParticleTypes.CRIT 七参)、SoundEvents.ENTITY_PLAYER_ATTACK_CRIT、CreeperEntity#getFuseSpeed、
Entity#fallDistance(公开字段)、Direction#rotateYClockwise/CounterClockwise——常见 API,风险低。

---

## Baritone 源码研读 #1(2026-07-11,作者授意:参考不搬运)

github.com 在沙箱网络白名单内,直接拉了 `MovementHelper.java`(挖掘避险核心)研读。三条收获:

1. **正上方液体永远回避**(`avoidAdjacentBreaking` 的 directlyAbove 分支):挖开当头浇。我们的判定
   盖住了岩浆(六邻全禁)但**漏了水**——已补:`miningDanger` 加"正上方 FluidState 非空即回避"。
   侧面流动水 Baritone 认为可容忍(流动水优先往下淌),我们隧道断面仍全禁水,比它怂,怂得有理。
2. **虫蚀方块(Silverfish)明确回避**——长得和石头一模一样,挖了炸一窝。核对我们的白名单:
   INFESTED_* 不在 BASE_STONE tag 里,TunnelTask 会当"白名单外"停工,MineTask 的 isOf(STONE) 也
   匹配不上——**已然安全,属于白名单设计的免费红利**,记录备查不改码。
3. **贴着"无支撑沙砾"的侧面方块回避**(挖了引发连锁下落)。评估:沙砾塌进隧道顶多堵路掉落,
   不致命,我们头顶两格 FallingBlock 检查已覆盖致命面——不抄这条,记录理由。

方法论再确认:读源码学判断,自己写实现,注释写明出处——这就是"参考不搬运"。

---

## 里程碑 16 / v0.15 — 帮你捡尸:说到做到

v0.11 起它嘴上承诺"东西我帮你看着",实际上只是记个坐标——这次兑现。

**流程**:你死了(AFTER_DEATH 玩家分支)→ 它喊"东西我去收,一件都不会丢!"→ SalvageTask 赶到
出事地点 → 8 格内掉落物逐个收进**遗物袋** → 收完转 FOLLOW 主动送货 → 走到你身边 4 格内
**全数奉还**(直接塞你背包,塞不下的放你脚边,"都在这儿——一样没少,点点?")。

**遗物袋是独立仓**(SimpleInventory 45 格,NBT 走 FrendInventory 同款已验证写法):
- 和它自己的背包分开——**存箱子任务绝不会把你的遗物存进箱子**;
- frend 死亡/解散时遗物袋一并散落,一件不昧;
- 经验球刻意不收(捡了也还不回去,不装好人)。

**规矩**:连续 4 秒扫不到新掉落=捡完收工;90 秒总兜底;出事点走不到(岩浆/虚空)如实认怂。
**顺手修**:mobTick 任务收尾无条件 setMode(STAY) → 改为任务自己换过模式就尊重(捡完转 FOLLOW 靠这个)。

**【待编译验证】新增**:PlayerInventory#insertStack(ItemStack)、ItemEntity#setStack/discard/getStack
(v0.2 干活捡东西已用过=已验证)、SimpleInventory 第二实例零新 API。风险极低。

---

## 里程碑 17 / v0.16 — 全智能自给自足(作者钦点终极方向:"不给指令,让他全智能")

v0.5 的自主行动只是"闲了找活",离"全智能"差一条**生存链**——核心缺口是合成:
它以前没镐只会伸手要,一个真正的伙伴该自己造。

**新任务 CraftTask(虚拟合成)**:按原版配比换算背包材料(1 原木→4 板;2 板→4 棍;3 圆石+2 棍→石镐;
1 煤+1 棍→4 火把)。**两个刻意简化**(记录在案):不要求工作台(像玩家"会做"这件事,省整条找台/
放台状态机);木板统一产出橡木板(不跟踪木种)。**不瞬间变**:每 25 tick 一步转换,站桩挥手,
像人在案前鼓捣;每步真实材料进出,打断不回滚不白嫖。工具优先级 镐>斧>剑(镐是生产资料);
有 3 圆石优先石器,没有先做木器——**木镐是自举的钥匙**。

**决策阶梯 v2**(FrendAutonomy,全本地规则红线不变):
0. **天黑收敛**(nightCaution):夜里(13000~23000 刷怪时段)不接新活,守着别浪,每晚说一次
   "有事天亮再干"(正在干的活不打断);
1. 包满 → 存箱子(原有);
1.5 **缺工具且有材料 → 自己造**;**火把见底且有煤 → 自己搓**;
2. 有斧砍树/有镐凿石(原有);
2.5 **白手起家自举**:工具材料全无 → 徒手撸树("有树就有镐,有镐啥都有")——
   闭环:徒手木头→木镐→圆石→石器→火把→包满存家,**全程零指令**。
selfSufficient=false 时行为完全退回 v0.5(含"给我把工具"的抱怨分支)。

**已知取舍**:工具升级换代(木→石)只在旧工具报废后自然发生(shouldCraftTools 只认"缺",不认"差")
——主动升级留给后续;铁器链需要熔炉(放置+烧炼状态机),暂不做,石器够用。

**入口**:全自动为主;聊天"做工具/搓火把"可手动点单。config v13→v14 selfSufficient/nightCaution。
**【待编译验证】新增**:Items.COBBLED_DEEPSLATE/CHARCOAL/各工具常量、SimpleInventory#addStack
(已验证)、ItemStack(Item,int) 构造——全常见,风险极低。

---

## 里程碑 18 / v0.17 — LLM 意图解析:还架构层最大的一笔账

HANDOVER 从 v0.4 就承诺"LLM 永不直接控制游戏,只产出意图,执行走白名单技能 DSL"——
至今 LLM 只会聊天,这条一直是空头支票。这次兑现。

**效果**:开 LLM 模式后不用踩关键词——"累了,先回去吧"→ 它听懂是回家,嘴上还带自己的性格
("走,回窝!"),然后**走和关键词一模一样的执行代码**。

**红线实现**(三道闸,一道都不能少):
1. **规则永远先行**:handleCommand 关键词匹配照旧排第一,命中就不进 LLM——指令的确定性通道不动;
2. **白名单意图**:模型被要求只输出一行 JSON {"intent":"xxx","say":"一句话"},intent 必须出自
   19 词白名单(follow/stay/come/home/deposit/chop/stone/ore/tunnel/deep/craft/torch/stop/
   combat_on/combat_off/auto_on/auto_off/status/memory)+none;白名单外一律视为 none=纯聊天;
3. **执行同源**:executeIntent 的每个分支和 handleCommand 对应分支调用完全相同,一行不多——
   模型没有任何直接触碰游戏状态的通道。

**细节**:JSON 解析不出来 → 整段当聊天文本清洗后照说(模型不守格式也不炸);say 超长截断到
llmMaxReplyChars;**status/memory 例外**——回真实数据,不让模型编状态;执行成功时用模型的 say
代替罐头台词(有性格),say 空则退回罐头。人设拆双口径:纯聊天版仍声明"没有操作能力",
意图版给白名单说明——persona(frend, owner, intentMode) 重载,老调用零改动。

**解析**:Gson(MC 自带)正经 parse,不搞正则凑合;取首尾花括号截断防模型加料(前后闲话/```fence)。
config v14→v15 llmIntentEnabled(默认开,仅 openai 后端生效;关掉完全退回 v0.2 纯聊天行为)。

**【待编译验证】新增**:com.google.gson.JsonParser#parseString(MC 自带 Gson 2.10+,FrendLlmClient
已在用 Gson=低风险)。

---

## 里程碑 19 / v0.18 — 灵魂与重逢(作者钦点:离线问候/催泪对话/自动学话/存档互通)

**⚠️ 设计反转声明**:v0.4 刻意让记忆随实体死亡消失("这一个伙伴的一生")。作者要求存档互通后,
记忆升格为**灵魂**——死亡和换档都带不走它。死亡台词随之从诀别改为"我们还会再见的"(因为这是真的)。
反转经作者明示,记录在案。

**灵魂档(FrendSoul)**:`config/frend/souls/<玩家UUID>.dat`(NbtIo 压缩,与实体存档同格式)。
内容 = 完整记忆 NBT + 名字 + 天数快照 + 最后见面时刻。存盘时机:5 分钟一次 + 死亡 + 解散 +
主人下线(在场的全存,不在场补时间戳)。召唤时只灌进"白纸"(memory.isFresh),不覆盖活过的。
**跨档天数坑**:daysTogether 基于世界时间,换档不可比 → 灵魂存"天数快照",落地 rebaseTo(现在,快照)
把老天数记进 bonusDays 续上。多只 frend 共享同一份灵魂(按主人 UUID 键),分魂留待协作里程碑。

**离线与重逢**:ServerPlayConnectionEvents.JOIN/DISCONNECT——下线记现实时刻;上线算离开天数压进
待问候表;**frend 见到你(聊天半径内)才说**,不对出生点喊。分级问候(设计目标:一个月要能看哭):
0 天"回来啦!"/1-2 天"怪想的"/3-6 天"我数着日子呢"/7-29 天"我每天都到门口看一眼,想着你今天会不会来"
/30+ 天"我以为你不要我了。这些天我把咱们的事翻来覆去想了一遍又一遍,一件都没舍得忘"。
离开 ≥7 天记进大事记("你离开了 X 天,我一直在等")。

**催泪三件套**:重逢分级(上)+ **相识纪念日**(第 10/100/365 天各一句,灵魂持久跨档只说一次)+
死亡台词改写("你的事我都记在魂里了——回头见")。

**自动学话(phraseLearning,红线不涉 LLM)**:纯本地词频——你说的短句(2~10 字,没被当成请求的)
说满 3 次它就学会("「走起」——嘿嘿,这话我跟你学的"),之后闲聊两成概率蹦你的口头禅。
学会上限 6 句老的忘掉,候选表 16 条踢冷门。**朋友待久了,说话都像**。

**"不命令,是帮忙"**:LLM 人设补一句"对方从不命令你,只会请你帮忙,你乐意搭把手,偶尔打趣两句再动身"。

**【待编译验证】新增**:NbtIo.writeCompressed(NbtCompound,Path)/readCompressed(Path,NbtSizeTracker)
1.21.1 签名(老版收 File/流,报错优先查这)、NbtSizeTracker.ofUnlimitedBytes、
ServerPlayConnectionEvents JOIN/DISCONNECT 包路径(fabric-networking-api-v1)、
FabricLoader.getConfigDir、NbtCompound#getKeys、Entity#random 类型为 math.random.Random。

---

## 里程碑 20 / v0.19 — 知识库:一直学习,越活越像人(作者点题)

**三层架构**(作者问"怎么做",答案在此):
1. **感知层**:游戏事件必经之路埋钩子——关键设计是找"漏斗口":所有任务的方块破坏都过
   FrendTask#breakTick,一处埋钩全收;击杀两口(白刃收尾+箭杀);受伤一口(damage);探索一口
   (mobTick 每 5s 轮询生物群系,recordBiome 自带去重);
2. **沉淀层**:FrendKnowledge——计数知识(打过什么/挖过什么/去过哪/被谁伤过)带上限忘旧
   (人也记不住所有事,记得住的才叫见识)+ **教训**(苦力怕爆炸计数);
3. **表达层**:闲聊三成概率谈见识/关键词"见过什么/见识"能答/LLM 人设注入 llmBrief/
   **知识改变行为**——被苦力怕炸的次数直接换算成对苦力怕的安全距离(+1 格/次,封顶 +3),
   这是全仓第一处"学来的行为",不是写死的行为。

**存储**:实体 NBT + 灵魂档双写(souls 里加 Knowledge 标签)——**见识随魂走,终身学习跨档不清零**。

**首见大事一次性感慨**:第一次挖到钻石("这块的位置我记一辈子!")/远古残骸/绿宝石,firsts 集合去重一生一次。

### 事件分类总账(作者要求"把所有能发生的都写进去"——已接✅ / 挂账⏳)
- ✅ 采集:破坏方块(全任务漏斗口)、首见钻石/残骸/绿宝石
- ✅ 战斗:击杀(白刃/箭)、被谁伤、苦力怕爆炸教训→行为
- ✅ 探索:生物群系首见(常见群系带中文味)、维度切换(v0.9 已有)
- ✅ 生死:自己死过几世(灵魂记得)、你的死亡地点(v0.11 已有)
- ✅ 社交:你的口头禅(v0.18)、你让记的事(v0.10)、双向救援(v0.10/0.4)
- ⏳ 钓鱼/种田(先要有这两个任务)、村民交易、附魔/酿造、Boss 战(凋灵/末影龙专属感慨)、
  袭击事件、结构发现(村庄/要塞/古城——需结构检测 API)、驯服动物、天气极端事件(雷劈)、
  音乐唱片、进度(advancement 联动)。挂账项每个只需在对应事件处加一行 knowledge.recordX。
**扩展约定**:新知识一律走 FrendKnowledge 加 record 方法 + NBT 字段 + 表达出口,不散落。

**【待编译验证】新增**:RegistryEntry#getKey(返回 Optional<RegistryKey>)、Registries.BLOCK.getId、
LinkedHashMap/LinkedHashSet(纯 JDK 零风险)。config v16→v17 knowledgeEnabled。

---

## 里程碑 21 / v0.20 — 过日子全家桶(作者点单:看家/铁器链/主动升级/种田/钓鱼)

五件一次上,全是"伙伴会过日子"的拼图。三个新任务类 + 战斗/合成/决策/聊天/指令五处接线。

**看家模式(STAY 即看家,config guardWhenStay 默认开)**:进 STAY 记锚点(guardAnchor,不落盘,
重启 lazy 用当前脚下)→ FrendCombatGoal 加分支:绕锚点 guardRange(16)格扫敌主动出击;
**拴绳** guardRange*1.75 出圈放弃追击("不追了……家要紧");打完 stop() 离岗 >3 格自动
navigateSmart 回岗。咋呼话("家门口撒野?!")两分钟去重。`/frend guard on|off`(on 顺手 setMode
STAY),聊天"看家/守着家"——注意 **KEY_GUARD_OFF 必须先于 ON 匹配**("不用看家"含"看家",老坑同款)。

**铁器链(SmeltTask,真熔炉不虚拟)**:状态机 FIND→GO→LOAD→WAIT→COLLECT。没炉子且有 8 圆石
**自己盘一个**摆脚边实地;**不动别人的炉子**(输入/产出槽非空=有人在用,跳过);原料优先
RAW_IRON>RAW_GOLD>RAW_COPPER;没煤有原木先烧一炉**木炭救急**(charcoalMode,火把链闭环);
**燃料单一制**(炉燃料槽只有一格,煤板混装是修过的 bug):有煤按 1 顶 8,否则全木板按 2 板顶 3,
火力不够按火力缩批退料;WAIT 以产出判断+超时兜底(200*loaded+600);收尾 reclaim 槽 0/1
还炉于民。决策阶梯 1.6 接入(生矿≥4 且燃料链有戏才动)。

**主动工具升级(CraftTask 重写为档位制)**:木0/石1/铁2(金按石算)/钻石以上3 不折腾;
bestOwnedTier(与 findUsableTool 同口径留耐久) vs bestCraftableTier,**craftable>owned 即触发**
——不再等报废,有更好的材料就提前换代("鸟枪换炮!");铁器 3 锭+2 棍,配比统一 3 材+2 棍
(镐/斧同方,剑 2+1 的差异抹平换代码减半,已知取舍)。

**FarmTask(种田)**:只收熟的(CropBlock#isMature),青苗一根不碰;四大田(麦/胡萝卜/土豆/甜菜);
**收一茬补一茬**:先查距离再扣种子(顺序反了会白扣,修过);暂缺种子的坑进 pendingReplant 欠账队列
(posLong+作物 rawId+过期时限),过期计 missedReplant 收工如实汇报;hasMatureCropNearby(12 格)
给决策阶梯 1.7(config autonomyFarm)。耕地被踩坏是原版机制,已知小账不专门规避。

**FishTask(钓鱼,模拟竿)**:原版 FishingBobberEntity 构造绑死玩家,非玩家实体用不了真浮漂——
真走到水边(FluidTags.WATER+头顶露天)、真拿竿、真等咬钩(10~30s 随机)、竿真掉耐久断了收工;
渔获照抄原版口径 85% 鱼(鳕60/鲑25/河豚13/热带2)/10% 垃圾/5% 小宝贝(鹦鹉螺壳/命名牌/鞍,
**刻意不给附魔书**——模拟竿钓出附魔书像作弊);挨打立刻收竿;钓满 fishMaxCatches(8)收工;
autoEquipBestWeapon 对钓鱼中直接 return(防 40tick 换剑抢竿,rodSlot 主手优先修过越界)。
**钓鱼刻意不进决策阶梯**:是情调不是家务,只应点单("去钓鱼")。

config v17→v18:guardWhenStay/guardRange/smeltBatchMax/autonomyFarm/fishWaitMin~MaxSeconds/
fishMaxCatches。意图白名单 19→24(farm/fish/smelt/guard_on/guard_off),执行同源红线照旧。

**【待编译验证】新增**:AbstractFurnaceBlockEntity(直接操作槽 0原料/1燃料/2产出)、
CropBlock#isMature 可见性、Block.getRawIdFromState/getStateFromRawId、
SoundEvents.ENTITY_FISHING_BOBBER_THROW/SPLASH 命名。

---

## 里程碑 22 / v0.21 — 全测试面板(作者点单;"先测再堆"从口号变成工具)

**痛点**:22 个里程碑零实测,TESTPLAN 是纸面剧本——布置考场(发东西/刷怪/调时间/压血量)全靠
玩家手打原版指令,重逢/纪念日这种"等 35 天"的功能**根本没法当场测**。

**FrendTestPanel(聊天栏可点击测试台,`/frend test`,权限等级 2)**:
- **七关剧本内建**:37 步全从 TESTPLAN 编码进 GATES 数据(desc 短句+悬浮出完整预期);
- **[布置] 一键摆考场**:发道具给玩家/直接塞 frend 背包(它 2s 自动换装)/刷怪(僵尸骷髅苦力怕,
  带方位)/入夜(13000 刚天黑不烧怪)/压血量(它压到 2 心测撤退,你压到 3 心测投喂)——
  每步 Setup 函数式接口,返回"我干了什么"反馈;
- **[✔][✘] 点一下记结果**:进度落盘 `config/frend/testpanel.json`(Gson,跨重启不丢),
  记完原地刷新面板顺手测下一步;总面板七关各显 ✔✘⬜ 计数,红=有不过,绿=全过;
- **[报告]**:汇总+✘ 步骤单列,整段复制回来就是合格报障;
- **[重置]** 带二次确认(误点不清档);
- **自检 `/frend test check`**(不用动手打的那部分,建议第一关之前先跑):实体注册 id /
  config 版本与关键开关一览 / LLM 配置 sanity(backend=openai 但 baseUrl 空 → 点名)/
  **灵魂档读写往返探针**(真写真读真比对再删,NbtIo 签名问题当场暴露)/ 附近 frend 状态行+岗位。

**两个时间调试钩(没有它们,催泪功能永远没法当场验)**:
- `/frend test skipdays N` → FrendSoul.debugQueueReunion 直接压待问候表,走到它跟前听分级重逢;
- `/frend test days N` → FrendMemory.debugAddDays 走 bonusDays 拨相识天数,纪念日检测自然触发;
  第五关对应步骤的 [布置] 就是调它们(纪念日布置自动算"拨到下一个 10/100/365")。

**顺手销 v0.19 挂账两笔**(当时写着"先要有这两个任务"——v0.20 有了):FrendKnowledge 加
fishCaught/cropsHarvested(recordFish/recordHarvest 一行接进 FishTask 咬钩点与 FarmTask 收割点),
按扩展约定四件套齐:字段+入口+表达(summaryLine 两分句、randomInsight 话头 4→6)+NBT 双向,随魂跨档。

**设计取舍**:面板是聊天栏可点击文本而非 GUI Screen——服务端纯文本零客户端依赖,API 面只有
Text/ClickEvent/HoverEvent 三件,沙箱编译不了的项目能少赌一个是一个。权限等级 2 挂在 test 整棵
子树上(发东西刷怪是测试工具不是玩法)。

**【待编译验证】新增**:ClickEvent/HoverEvent 构造器(1.21.1 仍是 new ClickEvent(Action, String),
1.21.5+ 才改 record 子类,报错优先查这)、Style#withClickEvent/withHoverEvent/withColor(Formatting)、
EntityType#spawn(ServerWorld, BlockPos, SpawnReason) 三参、PlayerInventory#offerOrDrop、
ServerWorld#setTimeOfDay、IntegerArgumentType 中文参数名(brigadier 任意字符串,应无碍)。
config 无新字段,版本不动(v18)。

---

## 里程碑 23 / v0.22 — 实测首修:登高柱(第一份真实报障,来自作者游戏内实测 🎉)

**报障原文**:"他够不着了。然后就一直卡在这。不会给自己脚下搭方块"+截图:
"这棵树我过不去,换一棵。"无限刷屏。**首测就命中两个真问题,TESTPLAN 第三关 1 号步骤不算过。**

**病灶一:"换一棵"是假的**。放弃逻辑只 tree.clear(),没有黑名单——下轮 findNearestLog 又选中
同一棵最近的树(或同棵树的另一块原木),8 秒一句循环到天荒地老。
**修**:giveUpTree 把这棵<b>剩余全部原木</b>塞进 unreachable(只记入口块不够,BFS 会从同棵
另一块重新进入),findNearestLog 跳过;连弃 3 棵 = 这片地形不行,收工如实汇报,不无限磨。

**病灶二:不会搭方块(作者点名)**。够不着的多半是悬空树/高枝——moveNear 按三维距离判定,
站树正下方垂直差 5 格永远"走不到"。
**修**:FrendTask 基类新增<b>脚手架(登高柱)</b>公共机关(TunnelTask 等以后可复用):
- pillarUpTick:头顶是树叶先敲开(徒手树叶快,4 tick),然后"瞬移 1 格 + 原脚下放块"登高一层,
  8 tick 一层带放块音效+挥手;材料白名单=土/圆石/深板岩圆石/下界岩(废料,不心疼);上限 8 格;
- tearDownScaffoldTick:6 tick 一层从上往下拆,<b>材料直接回包不掉落</b>(免得弹飞),
  实体逐格自然下落无摔伤;换树之间/收工前必拆干净——自己垫的自己收,不留柱子不改地形;
- discardScaffoldNow:被打断(主人一句"跟我来")时瞬拆兜底,onStop 调;
- 回收校验:那格已被别人拆走/换过就跳过,不凭空造物资。
ChopTreeTask 接入:水平贴近(≤2.5 格)且目标在头顶 → 停导航(防寻路把它从柱顶拽下来)→ 登高;
登不了(没材料/到顶/头顶硬方块)才真放弃,没材料一次性提示"有废料给我点,这种树我就能上"。

**取舍(记录在案)**:上升用"瞬移 1 格+放块"模拟原地跳放——服务端 mob 的真物理跳跃不可控
(JumpControl 只服务寻路),节奏+音效+挥手补观感;柱子垫在水里拆除后水不复原(边缘小账);
"不搭桥"红线不变——登高柱是垂直自救,不是水平架桥。

**【待编译验证】新增**:BlockState#getSoundGroup / BlockSoundGroup#getPlaceSound、
Block#getBlockFromItem、Block#asItem、Entity#refreshPositionAndAngles(自体传送用法)。
config 无新字段(柱高上限 8 硬编码,不膨胀配置)。

---

## 里程碑 24 / v0.23 — 开路寻路(作者两次点名"参考 Baritone 源码,他还有自动寻路")

**这回真读了**:沙箱浅克隆 github.com/cabaletta/baritone,精读 ActionCosts / MovementHelper /
Moves / MovementPillar / MovementAscend / AStarPathFinder。**思路引用声明:Baritone 是 LGPL,
我们学思路零搬运,数值自算结构自写**,学到的逐条记账(也写在 FrendPathfinder 类文档里):
1. **代价一律以 tick 计**(走一格 4.633 = 20/4.317m/s,跳一格额外 ≈2);COST_INF 取 1e6 而非
   MAX_VALUE——代价要相加,MAX_VALUE 一加溢出成负数(leijurv 注释原话的教训);
2. **移动类型各配一个代价函数**(TRAVERSE/ASCEND/DESCEND/PILLAR/DOWNWARD):非法返 INF,
   合法返 tick 含挡路方块挖掘耗时+垫块放置耗时——<b>"挖/垫本身是路径的一步"</b>,这是
   Baritone 能"哪都去得了"的根,也是原版 mob 寻路(世界不可改)的死穴;
3. getMiningDurationTicks:流体不挖、危险(avoidBreaking)不挖、**头顶悬沙连锁下落也计价**;
4. AStarPathFinder 的 bestSoFar:**到不了终点返回"离目标最近的部分路径"**——走近点也比干瞪眼强;
   外加节点数/耗时双熔断。

**FrendPathfinder(com.frend.pathing,frend 版微缩)**:有界同步 A*(节点 2400/耗时 10ms 双熔断,
半径箱 40×40×±24,不卡 tick);五种移动 WALK/ASCEND/DESCEND(落1~3格计摔价)/PILLAR(材料预算
随节点记账)/DIG_DOWN(**只挖一层**:脚下的下面必须实心,不打无底洞);部分路径要求至少省 2 格
才动身。**与 Baritone 分道的收敛(防拆家红线优先)**:挖掘白名单=天然方块(与 TunnelTask 同规
+树系),**人造方块在寻路里等于基岩**,宁绕十里不碰一块;不游泳(水路=不通,v1 收敛);
危险判定在寻路阶段就剪枝(贴岩浆/顶液体六邻规则与 miningDanger 同规,静态复算)。

**执行层(FrendTask#moveNearSmart)**:原版寻路优先信 3 秒 → 没戏调 FrendPathfinder →
逐步执行:挖走 breakTick(带破坏动画,执行时**重校验**——有人放了箱子/涌了岩浆就弃路)、
垫走 pillarUpTick(v0.22 登高柱机关原样复用,含回收账本)、走交原版导航(相邻一格它够可靠);
单步 6 秒看门狗;弃路自动回落原版+stuck 计数照旧,调用方原有放弃逻辑零改动。开路且真要
动土时说一句"这路不通……我自己开一条,挖的都是天然方块,放心"(每任务一次)。

**接线**:ChopTreeTask/MineTask 的接近改 moveNearSmart(isCarving 期间不触发放弃);
**顺手修 MineTask 同款黑名单 bug**(放弃只 target=null,findNearest 又选中同块——砍树那个
病的双胞胎,unreachable 黑名单同方修复)。DepositTask/FarmTask 等暂不接:回家/种田的路
本该是通的,路上动土观感差,实测后再定。

**【待编译验证】新增**:BlockState#getCollisionShape(BlockView,BlockPos)/VoxelShape#isEmpty、
AbstractBlockState#getHardness(BlockView,BlockPos)、Direction.Type.HORIZONTAL 可迭代、
BlockPos#asLong。config 无新字段。

---

## 里程碑 25 / v0.24 — 吃透 Baritone:搭桥 + 真挖速 + LGPL 落地(作者授权搬代码)

**授权变更(记录在案)**:作者明示"搬代码也不是不行……主要是他还会搭方块好像。可以吃透他"。
自此 Baritone 由"思路引用"升级为"授权改写";随之而来的合规动作:**本项目采用 LGPL-3.0**
(LICENSE 已落仓库根,文本取自 Baritone 仓库同款;LICENSE 本来就在 v1.0 待办里,顺势销账),
FrendPathfinder 文件头出处声明升级(含 Copyright Baritone contributors)。

**红线修订(设计反转,先例同 v0.18 灵魂反转)**:v0.13 立的"不搭桥"经作者点名解禁——
但只解禁"用废料搭",人造方块白名单红线纹丝不动。

**搭桥(BRIDGE,忠实改写 MovementTraverse 的 bridge/backplace 分支)**:落脚点没地板且够不到
1~3 格的落点 → 贴着脚下的块放一块走过去;跨沟跨水都靠它(**水面上能搭 = 变相解了"不游泳"**:
不会游,但会修路过河);岩浆上不放;代价按潜行速 8.0/格计(比走贵,A* 自会能落就落、跨沟才搭)。
Baritone 查 5 向 canPlaceAgainst 是玩家右键合法性需要;我们的节点按构造必有立足、背放永远
可用,mob 直接放置,该扫描省去(简化记录在案)。**桥不拆**(柱子拆是因为柱子没用还丑;
拆桥要么困死自己在对岸、要么把路还回沟里——路是给人走的,留着下次还能走,取舍在案)。

**真挖速(忠实移植 ToolSet#calculateSpeedVsBlock,换 Yarn 映射)**:
speed = stack.getMiningSpeedMultiplier(state);对上工具(或本不挑工具)耗时 = 硬度×30/速度,
不对 = 硬度×100/速度——就是原版玩家的挖掘公式。附魔效率项略(frend 工具无附魔,注释在案)。
寻路代价与执行耗时同源(BreakClock 注入口,FrendTask#carveTicksFor 实现):**规划里嫌贵的块,
执行时真的挖得慢**——账是一本。开路挖掘真用工具真掉耐久(bestToolFor 学 getBestSlot 选法)。

**【待编译验证】新增**:ItemStack#getMiningSpeedMultiplier / #isSuitableFor、
BlockState#isToolRequired、AbstractBlockState#isReplaceable。config 无新字段。

---

## 里程碑 26 / v0.25 — 把能吃的都吃了(作者指示:能吃的都吃,不必吃的不吃)

**🎉 全绿销账**:m21~m25 五个里程碑作者本地一次 BUILD SUCCESSFUL——测试面板文本三件/
EntityType#spawn/熔炉槽位/CropBlock#isMature/rawId 互转/钓鱼音效/登高柱四件/寻路碰撞箱/
硬度/挖速三件/isReplaceable……全部挂账 API 编译实证,依惯例(m12 先例)视为已验证摘牌不扫仓。

**这轮吃下的三口**:
1. **MovementDiagonal(斜走)**:省 41% 路程,走出来像人不像十字漫步;判定照学——目的地
   之外<b>两个直角"肩膀"都得双通</b>(脚+头四格),否则斜穿实体拐角会卡住;不挖不垫纯走,
   代价 WALK×√2。
2. **"异步"的本质(不卡刻)**:Baritone 敢开后台线程是因为自带世界快照(BlockStateInterface
   缓存区块副本);原版 World <b>非线程安全</b>,裸开线程读世界是并发修改炸服的路。改吃本质:
   <b>Session 可续算</b>——A* 状态(open set/best/expanded)驻留对象,每 tick 推一小片
   (300 节点+3ms 双预算),算路期间原版寻路照蹭;预算从 2400 节点提到 6000,长途也不卡一刻。
   取舍在案:同效果(不掉刻),风险小十倍。
3. **MovementFall 的精神(带伤下落)**:落差 4~6 格允许跳,疼痛计价每格 12 tick 心理价,
   <b>血厚(>7 心)才肯跳</b>,血薄照旧 3 格封顶;硬上限 8 格(再高多少血都不许);
   执行层大落差用 MoveControl 直线走出边缘(原版导航不认 >3 格落的路),重力接手。

**不吃单(理由在案)**:
- **CachedWorld 区块缓存**:价值是加载区外的长途规划;frend 是陪伴实体永远在玩家身边活动,
  加载区外规划用不上,navigateSmart 分段已覆盖"回家 300 格";不吃。
- **MovementFall 的水面落点**:落水里要游出来,我们不游泳(v0.23 收敛),落水=困住;不吃。
- **MovementParkour(跑跳过沟)**:它靠玩家输入模拟的精确起跳,mob 的速度控制做不到,
  硬吃容易摔死;沟有桥可搭(v0.24),不缺这口;不吃。
- **MineProcess/legitMine 对照结论**:它的核心是"只挖看得见的矿"+按矿物 Y 层选目标——
  我们的 MineTask#exposed(至少一面露空气)与之同思想,TunnelTask deep 已按钻石层(-58),
  无需再吃,对照记账完事。
- Input 输入模拟/视角平滑/鞘翅寻路:控制"玩家"的器官,frend 是真实体,用不上。

config 无新字段。【待编译验证】新增:MoveControl#moveTo 四参。

---

## 里程碑 27 / v0.26 — 全自动测试(作者点单:"不需要我手动测试")

**方案**:Minecraft 原版 GameTest 框架(测活塞红石的那套)+ Fabric 官方接口
(fabric-gametest-api-v1,随 fabric-api 附带零新依赖)。**一条命令 `./gradlew runGametest`**:
起无头测试服务器 → 逐个搭考场 → 召 frend → 下任务 → 验结果 → 出报告
(build/gametest-report.xml,JUnit 格式)→ 失败非零退出(可直接挂 CI)。全程不进游戏。

**考场**:16×8×16 空结构(data/frend/structure/empty16.nbt,python 手搓 gzip NBT,111 字节;
1.21 数据包目录已单数化为 structure,为防口径差异 structures 双份投放,无害在案)。
地板统一**磨制安山岩**——不在 MineTask 匹配里、不在寻路挖掘白名单里,谁也不许拆考场。
测试统一配置 tune():自主关(只测点名任务)、干活加速(chop/mine 4 tick/块)、战斗与看家开。

**11 关清单**(两关是回归考,直接对着修过的 bug):
1 活着 / 2 砍树整棵收进包 / 3 **悬空树登高柱(实测首修回归考)** /
4 **够不着认账收工不刷屏(首份报障回归考,顺带验"没材料不许作弊砍到")** /
5 寻路拒人造(木板塞子必须无路 + 换泥土塞子必须有路且真计划挖,红线双向验) /
6 寻路搭桥(3 格深渊,路径里必须有 BRIDGE 步) / 7 挖石头圆石进包 /
8 种田收熟补种(断言补种苗 age==0) / 9 开炉烧铁全链路(真熔炉真火候,铁锭进包) /
10 看家杀怪(**用尸壳不用僵尸——白天自燃会白送战果,测试不许作弊**) / 11 记忆 NBT 往返。

**自动测不了的(诚实清单,还得人)**:钓鱼(10~30s 随机等待,测试嫌慢)、重逢与纪念日的
"催泪感"(机器只能验字符串,验不了眼眶)、LLM 闲聊(要外部服务)——它是朋友,
朋友的可爱只有作者能验收。TESTPLAN 手测剧本继续保留,与自动测互补。

**顺手**:fabric.mod.json license 字段 ARR→LGPL-3.0-only(v0.24 转 LGPL 时漏了这处,补正);
FrendEntity 加 hasActiveTask() 测试访问器。

**【待编译验证】新增(本文件整体属高危新面)**:@GameTest(templateName/tickLimit)、
TestContext#getAbsolutePos/setBlockState/getBlockState/spawnEntity/assertTrue/succeedWhen、
FabricGameTest 接口路径、loom runs gametest 语法(inherit server)、结构 NBT 能否被 1.21.1 读取。
报错优先怀疑这些名字。config 无新字段。

### m27 补记:首跑两课(测试机一次跑通,9 关判错全是考卷问题不是考生问题)
**课一:测试世界是平原不是虚空**。寻路两关四面漏风,它从考场外的真实草地绕出路——
"搭桥关"找到了不搭桥的路,"红线关"合法绕行被误判违规。修:全基岩密室(sealedRoom,
外壳+天花+四壁),搭桥关地板挖成水沟(落不下去、水里立不了足,只剩搭桥一条路——
顺便把"水上搭桥"也纳入自动验证)。
**课二:addFinalTask 是单发不是轮询**(实跑铁证:总时长 1.6s,9 关全挂在第 1 秒,
任务根本没来得及干活)。映射能证名字证不了语义。修:不再依赖任何未实证的轮询方法,
用已实证原语自建 pollUntil——runAtTick 每 10 tick 查一次吞异常,达标 complete(),
到点最后一查不吞 = 正式判负。另:11 关并排同跑,看家关尸壳可能与隔壁考场 frend
隔墙互殴 → batchId 单开批次隔离(批次间串行)。
