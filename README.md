# frend

本地运行的 Minecraft 陪伴机器人 · **Fabric 1.21.1**

> 不是"命令机器",而是像朋友一样陪你生存、聊天、干活、打怪、探险、通关的本地 AI 朋友。完全离线,不依赖网络。

完整设计蓝图见 [`docs/DESIGN.md`](docs/DESIGN.md)。本仓库当前进度:**v0.2 已落地** —— 能出生、能跟随、能聊天、能干活(可选 LLM 闲聊)。

---

## 一、当前已实现(v0.1)

| 能力 | 说明 | 关键文件 |
|---|---|---|
| 类玩家 NPC | 玩家体型 + 玩家模型 + Steve 皮肤渲染;主人绑定,不会自然消失 | `entity/FrendEntity`、`client/render/FrendRenderer` |
| 三种模式 | 跟随(小跑、不贴脸)/ 停留 / 回家(到家自动转停留、走不过去会说明) | `entity/FrendFollowOwnerGoal`、`entity/FrendGoHomeGoal` |
| 规则层聊天 | 公屏说"跟我来 / 停下 / 过来 / 回家 / 报告状态"等关键词直接指挥;回话带随机延迟不秒回;没听懂且没在跟它说话就保持沉默不刷屏 | `system/FrendChatHandler` |
| 可选 LLM 闲聊 | `chatBackend` 设为 `openai` 后,闲聊接 OpenAI 兼容接口(本地 Ollama / LM Studio / 云端 OpenAI 同协议);带对话记忆与"对话延续窗口";失败/超时自动退回规则模板;**指令关键词永远走规则,模型永不控制游戏** | `system/FrendLlmClient` |
| "像人"细节 | 主人低血提醒(带冷却)、偶尔闲聊、受伤会喊、死前留遗言、跑丢才兜底传送并交代一句 | `entity/FrendEntity`、`system/FrendScheduler` |
| 基础背包 | 27 格,主人右键打开(原版箱子界面);死亡/解散全部掉落;NBT 持久化 | `entity/FrendEntity` |
| 家系统 | `/frend home set` 定家(记维度);"回家"走寻路,跨维度会说走不过去 | `entity/FrendEntity` |
| 干活 v0.2 | 砍树(整树)、挖石头、挖煤铁(必须有镐、只挖露头)、回家存箱子(工具干粮自留);慢慢挖带破坏动画不瞬爆;每次上限 32 块收工汇报;掉落物自动进背包 | `entity/task/` |
| 工具与吃饭 | 工具耐久留口气不用报废;血低自动吃背包里的食物(饱食度换算回血) | `entity/FrendEntity` |

## 二、指令

不需要 OP,人人可用(只作用于**自己的** frend):

```
/frend summon      召唤(默认每人 1 个,可配)
/frend follow      跟随
/frend stay        停留
/frend come        过来
/frend home set    把家定在你脚下
/frend home go     让它回家
/frend status      汇报状态(含战绩)
/frend memory      口头回忆(相识天数/击杀/救主/大事记)
/frend work chop     砍树
/frend work stone    挖石头
/frend work ore      挖煤和铁
/frend work deposit  回家把东西存箱子(工具干粮自留)
/frend work stop     收工
/frend auto on|off   自主行动开关(默认开:待命时自己砍树/凿石/存箱子)
/frend dismiss     解散(先掉落背包)
```

聊天关键词(附近 16 格内说话即可,无需指令):跟我来 / 停下 / 过来 / 回家 / 报告 / 砍树 / 挖石头 / 挖矿 / 存箱子 / 收工 / 保护我 / 别打了 / 自由活动 / 别自作主张 / 还记得吗 / 战绩 / 你好 / 谢谢……

**自主行动(v0.5,默认开)**:不用你下命令——待命超过 45 秒它就自己找活:包快满了自己回家存箱子;有斧头就去砍树,有镐子就去凿石头;开工前会打招呼,喊一声"收工"或"跟我来"随时打断。跟随时绝不擅自离队。日出/日落/下雨还会跟你搭句话。

## 三、构建

需要 **JDK 21**。

```bash
./gradlew build
```

产物在 `build/libs/frend-0.1.0.jar`,连同 Fabric API、Fabric Loader 丢进 `mods/` 即可。

> ⚠️ 本工程在沙箱内编写,沙箱网络未放行 Fabric/Mojang Maven 源,**未能在沙箱实际跑通 `./gradlew build`**。代码按 1.21.1 + Fabric API 编写并尽量照抄 yongye 仓库已编译验证过的同款 API;个别无先例的写法(玩家模型渲染器、聊天事件)已在代码注释标注「待编译验证」,报错回传即修。

## 四、配置

首次运行生成 `config/frend.json`,可调项:每人 frend 上限、跟随距离/速度/跑丢传送开关、聊天半径与回话延迟、闲聊开关与冷却、低血提醒阈值、被动回血等。

### 聊天大脑(默认纯本地,可选 LLM)

```jsonc
"chatBackend": "rules",                        // 默认:纯本地关键词规则,零依赖零联网
// 想要更像真人的闲聊,改成 "openai" 并按需调下面几项:
"openaiBaseUrl": "http://localhost:11434/v1",  // 默认本地 Ollama(先 ollama pull qwen2.5:7b 并 ollama serve);云端 OpenAI 用 https://api.openai.com/v1
"openaiApiKey": "",                            // Ollama 留空;云端填 key
"openaiModel": "qwen2.5:7b",                   // 云端例:gpt-4o-mini
"llmPersonaExtra": ""                          // 给 frend 加口头禅/性格
```

不管哪个后端:**指令关键词(跟我来/停下/过来/回家/报告)永远走本地规则**,LLM 只负责闲聊文本,输出永远不会被解析成游戏操作;请求全异步不卡服,失败/超时自动退回规则模板。

## 五、路线图(见 docs/DESIGN.md)

- ~~v0.2 能干活~~:✅ 已落地(砍树、挖石、挖煤铁、回家存箱子、工具耐久、自动吃)
- **v0.3 能战斗**:打怪、盾防、低血撤退、支援主人
- **v0.4 本地 LLM**:Ollama/llama.cpp 桥接,自然语言 → 白名单技能 DSL(LLM 永不直接控制游戏)
- **v0.5 矿洞与下界** → **v1.0 完整陪伴生存** → **v2.0 末影龙与速通**

## 六、边界

frend 不是外挂,不是刷材料机器。单机和自建服务器里可以做得很强;**不做**在公开服务器上隐藏机器人身份或绕过反作弊的功能。
