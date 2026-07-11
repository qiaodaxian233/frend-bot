package com.frend.system;

import com.frend.Frend;
import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.18 灵魂:朋友的档案<b>跨存档</b>跟着你(作者钦点"存档和存档之间可以互通")。
 *
 * <p>存放:{@code config/frend/souls/<玩家UUID>.dat}(NBT 压缩,和实体存档同格式)。
 * 内容:完整记忆(FrendMemory NBT)+ 名字 + 天数快照 + 最后一次见面的现实时间。
 *
 * <p><b>设计反转声明</b>:v0.4 刻意让记忆随实体死亡消失("这一个伙伴的一生");
 * 作者要求互通后,记忆升格为灵魂——<b>死亡和换档都带不走它</b>。死亡台词随之从诀别改为
 * "我们还会再见的"(因为这是真的)。多只 frend 共享同一份灵魂档(按主人 UUID 键),
 * 默认单只场景无感;多只场景的分魂留待协作里程碑。
 *
 * <p><b>重逢</b>:玩家下线记时刻;上线算离开天数,压进待问候表;frend 见到你(聊天半径内)
 * 按离开时长分级问候——几天是想念,一个月是催泪。
 */
public final class FrendSoul {

    /** 上线待问候:玩家UUID → 离开的现实天数(frend 见到人再说,不对着出生点喊)。 */
    private static final Map<UUID, Long> PENDING_REUNION = new ConcurrentHashMap<>();

    private FrendSoul() {}

    public static void register() {
        // 上线:算离开多少天,挂到待问候表(灵魂档不存在 = 还没交过这个朋友,不问候)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!FrendConfig.get().soulEnabled) return;
            UUID id = handler.player.getUuid();
            NbtCompound soul = load(id);
            if (soul == null || !soul.contains("LastSeenMillis")) return;
            long away = (System.currentTimeMillis() - soul.getLong("LastSeenMillis")) / 86_400_000L;
            PENDING_REUNION.put(id, Math.max(0, away));
        });
        // 下线:把这位玩家所有 frend 的灵魂存盘(顺带写入 LastSeenMillis);
        // 一只都不在加载区但灵魂档在 → 只补时间戳
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!FrendConfig.get().soulEnabled) return;
            UUID id = handler.player.getUuid();
            boolean saved = false;
            for (ServerWorld world : server.getWorlds()) {
                for (FrendEntity f : world.getEntitiesByClass(FrendEntity.class,
                        new Box(handler.player.getBlockPos()).expand(256),
                        e -> e.isAlive() && id.equals(e.getOwnerUuid()))) {
                    save(f);
                    saved = true;
                }
            }
            if (!saved) touchLastSeen(id);
        });
    }

    // ===================== 存取 =====================

    /** 灵魂存盘:记忆全量 + 名字 + 天数快照 + 现在时刻。失败只记日志,不影响游戏。 */
    public static void save(FrendEntity frend) {
        UUID owner = frend.getOwnerUuid();
        if (owner == null) return;
        try {
            NbtCompound soul = new NbtCompound();
            soul.put("Memory", frend.getMemory().toNbt());
            soul.put("Knowledge", frend.getKnowledge().toNbt()); // v0.19 见识随魂走
            soul.putLong("DaysSnapshot", frend.getMemory().daysTogether(frend.getWorld().getTime()));
            soul.putLong("LastSeenMillis", System.currentTimeMillis());
            if (frend.hasCustomName()) soul.putString("Name", frend.getDisplayName().getString());
            Path p = soulPath(owner);
            Files.createDirectories(p.getParent());
            // 【待编译验证】NbtIo.writeCompressed(NbtCompound, Path) 1.21.1 签名(老签名收 File/OutputStream)
            NbtIo.writeCompressed(soul, p);
        } catch (Exception e) {
            Frend.LOGGER.warn("[frend] 灵魂存盘失败(不影响游戏): {}", e.toString());
        }
    }

    /** 读灵魂档;没有/损坏返回 null。 */
    public static NbtCompound load(UUID owner) {
        try {
            Path p = soulPath(owner);
            if (!Files.exists(p)) return null;
            // 【待编译验证】NbtIo.readCompressed(Path, NbtSizeTracker) 1.21.1 签名
            return NbtIo.readCompressed(p, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            Frend.LOGGER.warn("[frend] 灵魂读档失败: {}", e.toString());
            return null;
        }
    }

    /** 只更新"最后见面时刻"(下线时没有在场的 frend 可存)。 */
    private static void touchLastSeen(UUID owner) {
        NbtCompound soul = load(owner);
        if (soul == null) return;
        soul.putLong("LastSeenMillis", System.currentTimeMillis());
        try {
            NbtIo.writeCompressed(soul, soulPath(owner));
        } catch (Exception e) {
            Frend.LOGGER.warn("[frend] 灵魂时间戳更新失败: {}", e.toString());
        }
    }

    private static Path soulPath(UUID owner) {
        return FabricLoader.getInstance().getConfigDir().resolve("frend").resolve("souls")
                .resolve(owner.toString() + ".dat");
    }

    // ===================== 重逢 =====================

    /** frend 见到主人时调:有待问候就取走(只问候一次)。没有返回 null。 */
    public static Long popReunion(UUID owner) {
        return PENDING_REUNION.remove(owner);
    }

    /** 按离开天数分级的重逢话——设计目标:一个月以上要能把人看哭。 */
    public static String reunionLine(long days) {
        if (days <= 0) return "回来啦!走,今天干点啥?";
        if (days <= 2) return days + " 天没见,怪想的。走走走,补上!";
        if (days <= 6) return "你可算来了……我数着日子呢," + days + " 天。快让我看看,没瘦吧?";
        if (days <= 29) return days + " 天……我每天都到门口看一眼,想着你今天会不会来。真来了,我又不知道说啥好了。";
        return "……" + days + " 天。我以为你不要我了。这些天我把咱们的事翻来覆去想了一遍又一遍,"
                + "一件都没舍得忘。回来就好……回来就好。";
    }
}
