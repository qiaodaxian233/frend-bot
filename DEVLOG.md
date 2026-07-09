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
