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
    public static final int CURRENT_CONFIG_VERSION = 1;
    public int configVersion = CURRENT_CONFIG_VERSION;

    // ============ 召唤 ============
    /** 每名玩家最多同时拥有几个 frend(按玩家附近 256 格内已加载实体计数,跨维度/远处未加载的数不到,先够用)。 */
    public int maxFrendsPerPlayer = 1;

    // ============ frend 属性 ============
    /** 最大生命(玩家同款 20)。 */
    public double frendMaxHealth = 20.0;
    /** 移动速度属性(人形怪常用 0.3;跟随时按 followSpeed 倍率小跑)。 */
    public double frendMoveSpeed = 0.3;

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
