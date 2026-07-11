package com.frend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全局配置。文件位于 config/frend.json,可在不改代码的前提下调行为。
 * 加载策略:文件存在则读入(缺字段用默认值),不存在则写出默认文件;读坏了退回默认并告警。
 */
public class FrendConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static FrendConfig INSTANCE;

    /** 配置 schema 版本。默认值重平衡时 +1,加载旧版本文件会在日志警告。 */
    public static final int CURRENT_CONFIG_VERSION = 11;
    public int configVersion = CURRENT_CONFIG_VERSION;

    // ============ 召唤 ============
    /** 每名玩家最多同时拥有几个 frend(按玩家附近 256 格内已加载实体计数,跨维度/远处未加载的数不到,先够用)。 */
    public int maxFrendsPerPlayer = 1;

    // ============ frend 属性 ============
    /** 最大生命(玩家同款 20)。 */
    public double frendMaxHealth = 20.0;
    /** 移动速度属性(人形怪常用 0.3;跟随时按 followSpeed 倍率小跑)。 */
    public double frendMoveSpeed = 0.3;
    /** 基础近战攻击力(无武器时)。 */
    public double frendAttackDamage = 2.0;

    // ============ 跟随 ============
    /** 与主人距离超过这个值(格)开始跟。 */
    public double followStartDistance = 5.0;
    /** 追到这个距离(格)就停,不贴脸。 */
    public double followStopDistance = 2.5;
    /** 跟随时寻路速度倍率(>1 = 小跑)。 */
    public double followSpeed = 1.15;
    /** 距离超过这个值(格)视为跑丢。 */
    public double teleportDistance = 48.0;
    /** 跑丢后是否允许兜底传送到主人身边(设计上尽量少瞬移,这是防卡死的保险丝)。 */
    public boolean allowTeleportWhenLost = true;

    // ============ 聊天 ============
    /** frend 能"听见"玩家聊天、说话能被听见的半径(格)。 */
    public double chatRadius = 16.0;
    /** 回话随机延迟范围(tick,20 tick = 1 秒)。像人:不秒回。 */
    public int replyDelayMinTicks = 8;
    public int replyDelayMaxTicks = 30;
    /** 是否允许 frend 偶尔主动闲聊(看风景、吐槽)。 */
    public boolean enableAmbientChat = true;
    /** 主动闲聊最短间隔(秒)。 */
    public int ambientChatCooldownSeconds = 240;

    // ============ 聊天大脑 ============
    /**
     * 聊天后端:
     * "rules"  = 纯本地关键词规则(默认,零依赖零联网);
     * "openai" = OpenAI 兼容接口——本地 Ollama / LM Studio 或云端 OpenAI 都是同一个协议,
     *            改 baseUrl/model/key 即可。只负责闲聊文本;指令关键词永远走规则,
     *            模型输出永远不会被解析成游戏操作(行为红线)。
     */
    public String chatBackend = "rules";
    /** OpenAI 兼容接口地址。默认指向本地 Ollama 的 /v1,全程不出网;云端 OpenAI 用 https://api.openai.com/v1。 */
    public String openaiBaseUrl = "http://localhost:11434/v1";
    /** API key。本地 Ollama 留空即可;云端服务填对应 key。 */
    public String openaiApiKey = "";
    /** 模型名。Ollama 例:qwen2.5:7b;OpenAI 例:gpt-4o-mini。 */
    public String openaiModel = "qwen2.5:7b";
    /** 单次请求超时(秒);超时/失败自动退回规则模板,绝不卡服务器主线程。 */
    public int openaiTimeoutSeconds = 12;
    /** LLM 回复最长字符数(像人:短句,超长截断)。 */
    public int llmMaxReplyChars = 60;
    /** 发给模型的最近对话条数(user/assistant 各算一条)。 */
    public int llmHistoryEntries = 10;
    /** 两次 LLM 请求最短间隔(秒),防止刷屏刷接口。 */
    public int llmMinIntervalSeconds = 3;
    /** 追加到人设提示词末尾的自定义设定(口头禅、性格等)。 */
    public String llmPersonaExtra = "";
    /** 对话延续窗口(秒):frend 刚说完话,这段时间内主人说话不用喊名字也算在跟它聊。 */
    public int conversationWindowSeconds = 15;

    // ============ 干活(v0.2) ============
    /** 找树/矿/箱子的搜索半径(格)。 */
    public double workSearchRadius = 16.0;
    /** 干活时"够得着"的距离(格),够不着先走过去。 */
    public double workReach = 4.5;
    /** 一次任务最多处理的方块数,到数收工汇报(防止一句"挖矿"挖穿地图)。 */
    public int maxBlocksPerJob = 32;
    /** 有对应工具时砍一块原木耗时(tick);没斧头翻倍。 */
    public int chopTicksPerBlock = 25;
    /** 有镐时挖一块石/矿耗时(tick);挖掘必须有镐。 */
    public int mineTicksPerBlock = 35;
    /** 工具剩余耐久 ≤ 该值就不再使用(给主人留口气修/换)。 */
    public int toolReserveDurability = 8;

    // ============ 自动进食(v0.2) ============
    /** 血量低于该值且背包里有吃的就自己吃(不再依赖被动回血)。 */
    public boolean autoEat = true;
    public double autoEatBelowHealth = 14.0;
    /** 两口饭之间的最短间隔(秒)。 */
    public int eatCooldownSeconds = 8;

    // ============ 照顾主人 ============
    /** 主人血量低时提醒。 */
    public boolean ownerLowHealthWarn = true;
    /** 低血阈值(点,6 = 三颗心)。 */
    public double lowHealthWarnThreshold = 6.0;
    /** 提醒冷却(秒),防刷屏。 */
    public int lowHealthWarnCooldownSeconds = 30;

    // ============ 生存 ============
    /** frend 自身缓慢回血(v0.2 之前还不会吃东西,先给被动回复保底)。 */
    public boolean passiveRegen = true;
    /** 回血间隔(tick)。 */
    public int regenIntervalTicks = 80;
    /** 每次回血量(点)。 */
    public double regenAmount = 1.0;

    // ===== v0.5 自主行动 =====
    /** 总开关:不等命令自己找活干(存箱子/砍树/凿石)+ 环境闲话。 */
    public boolean autonomyEnabled = true;
    /** STAY 待命闲置多少秒后自主开工。 */
    public int autonomyIdleSeconds = 45;
    /** 一次自主决策后的冷却(秒)——防抽风连环决策。 */
    public int autonomyCooldownSeconds = 120;
    /** 背包非空格占比达到多少,自己回家存箱子(0~1)。 */
    public double autonomyDepositAtFullness = 0.7;
    /** 自主干活必须有对应工具(false = 允许徒手砍树,不推荐,磨叽)。 */
    public boolean autonomyRequireTool = true;
    /** 环境闲话:日出/日落/下雨说一句(一天各一次,主人在旁边才说)。 */
    public boolean autonomyChatter = true;

    // ===== v0.6 矿下安全 =====
    /** 自动插火把:身处黑暗(方块光和天空光都低 = 在洞里)且背包有火把 → 脚下插一根。 */
    public boolean autoTorch = true;
    /** 光照阈值:低于此值算"太黑"(原版怪物在方块光 0 刷新,7 留足余量)。 */
    public int torchLightThreshold = 7;
    /** 挖掘避险:目标方块贴着岩浆、或头顶是沙/沙砾 → 跳过不挖。false = 艺高人胆大(不推荐)。 */
    public boolean mineSafetyEnabled = true;

    // ===== v0.7 装备 =====
    /** 自动穿甲/拿盾:背包里有更好的盔甲就穿上,副手空着有盾就拿上。 */
    public boolean autoEquipArmor = true;

    // ===== v0.8 弓箭远程 =====
    /** 远程战斗:包里有弓有箭,目标远就拉弓,近了自动换回剑斧。 */
    public boolean rangedEnabled = true;

    // ===== v0.9 下界适应 =====
    /** 自卫反击:被任何非玩家生物打了就还手(任何模式都生效;不打玩家不打同类的红线不变)。 */
    public boolean selfDefense = true;
    /** 跨维度跟随:主人进下界/末地后,跟随中的 frend 自动追过去。 */
    public boolean crossDimensionFollow = true;

    // ===== v0.11 有来有往 =====
    /** 你血量见底时,它包里有吃的就扔一份给你(不只是嘴上提醒)。 */
    public boolean shareFoodWhenOwnerLow = true;
    /** 记住你倒下过的地方,路过时提醒你小心。 */
    public boolean deathSpotWarn = true;

    // ===== v0.12 路径规划 =====
    /** 路过木门自己开、走过随手关(村民同款寻路)。 */
    public boolean openDoors = true;
    /** 卡死自救:走路卡住先跳一下,还不行就换条路重算。 */
    public boolean stuckRescue = true;

    // ===== v0.13 挖矿路径规划 =====
    /** 隧道/下矿单次掘进步数上限(平巷每步 2 块,楼梯每步 3 块,不受 maxBlocksPerJob 约束)。 */
    public int tunnelMaxLength = 48;
    /** 下矿目标矿层(楼梯挖到这个 Y 再转平巷;1.18+ 钻石密集层约 -58)。 */
    public int deepMineTargetY = -58;

    public static FrendConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("frend.json");
        if (Files.exists(path)) {
            try {
                INSTANCE = GSON.fromJson(Files.readString(path), FrendConfig.class);
                if (INSTANCE == null) INSTANCE = new FrendConfig();
                if (INSTANCE.configVersion < CURRENT_CONFIG_VERSION) {
                    Frend.LOGGER.warn("[frend] 配置文件版本较旧(v{} < v{}),可删除 config/frend.json 重新生成以获得新默认值",
                            INSTANCE.configVersion, CURRENT_CONFIG_VERSION);
                }
            } catch (Exception e) {
                Frend.LOGGER.error("[frend] 读取 config/frend.json 失败,使用默认配置", e);
                INSTANCE = new FrendConfig();
            }
        } else {
            INSTANCE = new FrendConfig();
            save();
        }
    }

    // ===== 战斗(v0.3) =====
    /** 是否启用战斗系统。 */
    public boolean combatEnabled = true;
    /** 主动清怪半径(格)。 */
    public double combatRange = 12.0;
    /** 是否使用背包里的剑/斧当武器。 */
    public boolean autoEquipWeapon = true;
    /** 是否允许举盾防御(副手需要有盾)。 */
    public boolean shieldEnabled = true;
    /** 血量低于此值时触发撤退(原始 HP,满血 20)。 */
    public double retreatBelowHealth = 6.0;
    /** 撤退持续时间(秒),结束后恢复战斗。 */
    public int retreatDurationSeconds = 8;
    /** 是否在主人被攻击时自动支援。 */
    public boolean supportOwner = true;

    public static void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("frend.json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(get()));
        } catch (Exception e) {
            Frend.LOGGER.error("[frend] 写出 config/frend.json 失败", e);
        }
    }
}
