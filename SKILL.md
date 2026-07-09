---
name: frend-bot
description: >
  在「frend」Minecraft Fabric 1.21.1 陪伴机器人 mod(仓库 qiaodaxian233/frend-bot)上写代码、加功能、
  改配置、提交推送时使用。规范沿用姊妹仓库 qiaodaxian233/yongye 的 SKILL.md(同作者同工作流,
  那边的坑这边一样会踩)。改任何 .java、改 FrendConfig、提交前,先读这份。
---

# frend mod —— 踩坑 & 自查手册

> 仓库:https://github.com/qiaodaxian233/frend-bot · Minecraft **Fabric 1.21.1** · 纯 Java,无前置(Fabric API 除外)。
> 配套文档:`HANDOVER.md`(接手须知 + 0.5 状态行=全里程碑历史)、`DEVLOG.md`(逐里程碑细节)、`docs/DESIGN.md`(产品蓝图)。**动手前都扫一眼。**
> 姊妹仓库 **qiaodaxian233/yongye**:同作者、同工作流、大量已编译验证的 1.21.1 代码。**拿不准的 MC/Fabric API,先去那边 grep 已有用法照抄,其次才是查文档,绝不凭记忆。**

---

## 0. 三条铁律(yongye 同款,作者明确要求)

1. **不装懂、不臆想**。状态类结论(凭据是否有效 / 代码是否正确 / 某 API 存不存在)先实测,或明确标「待编译验证」,绝不凭印象下结论。
2. **话少**。交活给结论 + 必要提醒,别长篇复述自己干了啥、别反复确认。"你推就完了。"
3. **沙箱会被反复清空**。每轮都可能丢:本地 repo、未提交改动、上传文件。**先 commit+push 落盘,别攒着**;每轮开工先重拉 + `git ls-remote` 核对远端 HEAD;别假设上轮的东西还在。

---

## 1. 环境:能做 / 不能做

| 能做 | 不能做 |
|---|---|
| 静态自检(括号、import 路径、JSON 合法性) | **在沙箱里编译 Fabric**(没放行 Fabric/Mojang Maven 源)→ 新 API 一律标「待编译验证」,作者本地 `./gradlew build` |
| 读 yongye/本仓库既有代码查 API 用法 | **凭记忆确认 MC/Fabric API 签名/包路径**(版本敏感) |
| 用作者临时给的 PAT 一次性 push | **凭沙箱自身凭据 push** → 没 PAT 就导 `.patch` 给作者 `git am` |
| 程序化处理已有贴图 | **画像素皮肤/UV 图集**(自定义 frend 皮肤要作者提供 64×64 标准玩家皮肤 png) |

**Fabric 编不了 ≠ 代码没错。** build 成功也只代表能装能跑,行为对不对仍须进游戏实测。

## 2. 开工前必做

1. `git ls-remote origin main` 确认远端 HEAD,重建任何东西前先确认远端是不是已经有了。
2. 读 `HANDOVER.md` 0.5 状态行(最新里程碑在最前),确认新里程碑号 = 当前 +1。
3. 要用的 MC/Fabric API:本仓库有 → 照抄;本仓库没有 → 去 yongye grep;都没有 → 写完标「待编译验证」并在 DEVLOG 里列出风险点。

## 3. frend 特有的红线与易混点

- **LLM 永不直接控制游戏**(v0.4 起):模型只产出意图 JSON/DSL,执行必须走白名单技能。任何"让模型直接跑代码/发指令"的捷径都不许开。
- **不刷屏**:frend 所有主动说话必须带冷却;没听懂且没被喊名字 → 沉默。
- **不瞬移是原则**:`teleportDistance` 兜底传送是防卡死保险丝,不是移动手段。
- **不做多人服欺骗**:不隐藏机器人身份、不绕反作弊。这是产品边界,不是 TODO。
- 关键词匹配是 contains 且**顺序敏感**(FOLLOW 在 COME 前),改 `FrendChatHandler` 关键词表要过一遍互相包含的情况。
- 模式(FOLLOW/STAY/GO_HOME)只在服务端 + NBT,**没有 DataTracker**;要做客户端可见状态再加,别顺手重构。

## 4. 1.21.1 版本敏感 API(本仓库已用到的)

- 属性还是 `EntityAttributes.GENERIC_MAX_HEALTH` 等 **GENERIC_ 前缀**(1.21.2 才改名)。
- `Inventories.writeNbt/readNbt` 第三参必须传 `RegistryWrapper.WrapperLookup`(实体侧用 `getWorld().getRegistryManager()`)。
- 实体 NBT 还是 `writeCustomDataToNbt/readCustomDataFromNbt(NbtCompound)`(更高版本才换 WriteView)。
- 事件包路径:`ServerTickEvents` 在 `fabric.api.event.lifecycle.v1`;聊天 `ServerMessageEvents` 在 `fabric.api.message.v1`【待编译验证】。
- 渲染【待编译验证】:`PlayerEntityModel(ModelPart, boolean thinArms)`、`EntityModelLayers.PLAYER`、Steve 贴图 `textures/entity/player/wide/steve.png`(1.20.2 起的新路径)。

## 5. 交活前自查清单

- [ ] 新增/改动的每个 .java:import 全、括号配平、没用 1.21.1 不存在的 API(或已标待编译验证)
- [ ] 新增台词都有冷却;新增配置项写进 FrendConfig 并带注释
- [ ] README 能力表 / DEVLOG 新条目 / HANDOVER 0.5 状态行,三处同步更新
- [ ] commit + push 成功(`git log origin/main -1` 核实),没 PAT 就交 .patch
- [ ] 回复作者:结论 + 待编译验证清单 + 需要作者做的事,不啰嗦

## LLM 后端(m2 加入)踩坑备忘

- **协议只有一个**:OpenAI `/chat/completions`。Ollama(`http://localhost:11434/v1`)、LM Studio、云端 OpenAI 全兼容,永远不要为某一家单写客户端。
- **零新依赖**:HTTP 用 JDK `java.net.http.HttpClient`,JSON 用 MC 自带 gson——别往 build.gradle 加 okhttp/jackson,会平白引入版本冲突风险。
- **线程红线**:CHAT_MESSAGE 回调在服务器主线程,网络请求必须 `sendAsync`;拿到结果后必须 `server.execute(...)` 切回主线程才能碰实体/世界。在主线程 `.join()`/`.get()` 等网络 = 卡服事故。
- **行为红线**:模型输出只当聊天文本广播,永远不解析成游戏操作。指令关键词在 `handleCommand` 里前置短路,轮不到 LLM。
- **防刷屏三件套**:单飞标志(llmBusy)+ 最短间隔(llmMinIntervalSeconds)+ 失败退模板。改聊天逻辑时三件都不能丢。
- **响应要清洗**:本地小模型可能吐 `<think>...</think>`、多行、整段引号——`FrendLlmClient.sanitize` 统一处理,新增后端也走它。

## 干活任务(m3 加入)踩坑备忘

- **关键词 contains 顺序又埋过一次雷**:"回家存箱子"含"回家",`KEY_DEPOSIT` 必须排在 `KEY_HOME` 前判定(与 FOLLOW-before-COME 同款)。往关键词表里加词前,先想想会不会被前面的截胡。
- **加 Mode 枚举值 = 全仓搜 switch**:`Mode` 加了 WORK,`FrendChatHandler` 里两个 switch 不补分支直接编译失败(Java 枚举 switch 要穷尽)。
- **ItemStack#damage 不要碰**:1.21 各小版本签名一直变(Entity/EquipmentSlot/ServerWorld+Consumer 好几套)。手动 `setDamage(getDamage()+1)` + 自己判断报废,稳。
- **破坏方块要"慢慢挖"**:`setBlockBreakingInfo(entityId, pos, 0..9)` 播进度、`-1` 清除;真正破坏用 `breakBlock(pos, true, entity)` 让掉落物照常掉。瞬爆 = 一眼假。
- **任务不落盘**:任务对象全是瞬时状态,readNbt 里 WORK 一律退 STAY——别试图序列化任务,收益低坑多。
- **搜方块用 BlockPos.iterate 一次性搜,结果缓存**:半径 16 是 33³≈3.6 万格,每次搜完缓存目标,挖完/失效再搜;千万别每 tick 全量扫。
