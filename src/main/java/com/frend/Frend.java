package com.frend;

import com.frend.registry.ModEntities;
import com.frend.system.FrendChatHandler;
import com.frend.system.FrendCommands;
import com.frend.system.FrendScheduler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 《frend》主入口 —— 本地运行的 Minecraft 陪伴机器人。
 *
 * v0.1(里程碑 1):
 *  - /frend summon / follow / stay / come / home set / home go / status / dismiss
 *  - 规则层聊天(不需要模型):听到附近主人说话,按关键词切模式 / 模板回话,带随机延迟不秒回
 *  - 类玩家 NPC:玩家体型 + 原版 Steve 皮肤渲染,跟随会小跑、跑丢兜底传送(可配)
 *  - 基础背包:27 格,右键打开,死亡/解散掉落
 *
 * 设计文档见 docs/DESIGN.md。后续路线:v0.2 干活(砍树/挖矿) → v0.3 战斗 → v0.4 本地 LLM(Ollama)。
 * 核心原则:LLM 永远不直接控制游戏,只产出意图;执行走内部白名单技能(v0.4 起)。
 */
public class Frend implements ModInitializer {
    public static final String MOD_ID = "frend";
    public static final Logger LOGGER = LoggerFactory.getLogger("frend");

    @Override
    public void onInitialize() {
        LOGGER.info("[frend] 你的朋友正在上线……");

        // 配置(先于实体属性注册,属性数值来自配置)
        FrendConfig.load();

        // 注册层
        ModEntities.init();

        // 系统层(事件 + tick 驱动,零 mixin)
        FrendScheduler.register();   // 延迟任务(聊天不秒回)
        FrendChatHandler.register(); // 规则层聊天
        FrendCommands.register();    // /frend 指令树

        LOGGER.info("[frend] 初始化完成。/frend summon 召唤你的朋友。");
    }
}
