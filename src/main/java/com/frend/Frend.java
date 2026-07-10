package com.frend;

import com.frend.entity.FrendEntity;
import com.frend.registry.ModEntities;
import com.frend.system.FrendChatHandler;
import com.frend.system.FrendCommands;
import com.frend.system.FrendScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 《frend》主入口。
 *
 * v0.1  召唤 / 跟随 / 规则聊天 / 背包
 * v0.2  砍树 / 挖矿 / 回家存箱 / 工具耐久 / 自动吃饭
 * v0.3  战斗:主动清怪 / 支援主人 / 盾牌格挡 / 低血撤退 / 自动装备武器
 */
public class Frend implements ModInitializer {
    public static final String MOD_ID = "frend";
    public static final Logger LOGGER = LoggerFactory.getLogger("frend");

    @Override
    public void onInitialize() {
        LOGGER.info("[frend] 你的朋友正在上线……");

        FrendConfig.load();
        ModEntities.init();

        FrendScheduler.register();
        FrendChatHandler.register();
        FrendCommands.register();

        // v0.3:主人被攻击 → 附近 frend 支援
        registerOwnerHurtListener();

        LOGGER.info("[frend] 初始化完成。/frend summon 召唤你的朋友。");
    }

    /**
     * 监听 LivingEntity 受伤事件:受伤者是玩家时,通知其附近的 frend 支援。
     * Fabric ServerLivingEntityEvents.ALLOW_DAMAGE — 返回 true 表示不拦截伤害。
     */
    private void registerOwnerHurtListener() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(
                (LivingEntity entity, DamageSource source, float amount) -> {
                    if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity player)) return true;
                    if (!(entity.getWorld() instanceof ServerWorld world)) return true;

                    LivingEntity attacker = null;
                    if (source.getAttacker() instanceof LivingEntity la) attacker = la;
                    if (attacker == null) return true;

                    final LivingEntity finalAttacker = attacker;
                    for (FrendEntity frend : world.getEntitiesByClass(
                            FrendEntity.class,
                            new net.minecraft.util.math.Box(player.getBlockPos()).expand(128),
                            f -> f.isAlive() && f.isOwner(player))) {
                        frend.onOwnerHurt(finalAttacker);
                    }
                    return true;
                });
    }
}
