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
 * "我们还会再见的"(因为这是真的)。v0.27 分魂兑现(协作里程碑):
 * 档案格式升 v2——{FormatV:2, LastSeenMillis, Frends:{"1":{...},"2":{...}}},每只朋友一个槽位,
 * <b>各自的名字、记忆、见识互不串档</b>。旧档(平铺格式)读到时视为 1 号魂原地迁移,下次存盘自然升级。
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

    /** 灵魂存盘(v0.27 按槽):记忆全量 + 名字 + 天数快照写进自己的槽位,现在时刻写顶层。 */
    public static void save(FrendEntity frend) {
        UUID owner = frend.getOwnerUuid();
        if (owner == null) return;
        try {
            NbtCompound root = loadRoot(owner);
            if (root == null) root = new NbtCompound();
            root.putInt("FormatV", 2);
            NbtCompound slot = new NbtCompound();
            slot.put("Memory", frend.getMemory().toNbt());
            slot.put("Knowledge", frend.getKnowledge().toNbt()); // v0.19 见识随魂走
            slot.putLong("DaysSnapshot", frend.getMemory().daysTogether(frend.getWorld().getTime()));
            slot.putInt("Skin", frend.getSkinIndex()); // v0.29 形象随魂走:换个天地还是那张脸
            if (frend.hasCustomName()) slot.putString("Name", frend.getDisplayName().getString());
            NbtCompound frends = root.contains("Frends") ? root.getCompound("Frends") : new NbtCompound();
            frends.put(String.valueOf(Math.max(1, frend.getSoulId())), slot); // 0=老档实体,归 1 号魂
            root.put("Frends", frends);
            root.putLong("LastSeenMillis", System.currentTimeMillis());
            Path p = soulPath(owner);
            Files.createDirectories(p.getParent());
            // 【待编译验证】NbtIo.writeCompressed(NbtCompound, Path) 1.21.1 签名(老签名收 File/OutputStream)
            NbtIo.writeCompressed(root, p);
        } catch (Exception e) {
            Frend.LOGGER.warn("[frend] 灵魂存盘失败(不影响游戏): {}", e.toString());
        }
    }

    /** v0.27 读根档并做 v1→v2 视图迁移(读时包一层,不写盘;下次 save 自然落成 v2)。 */
    public static NbtCompound loadRoot(UUID owner) {
        NbtCompound root = load(owner);
        if (root == null) return null;
        if (!root.contains("Frends") && root.contains("Memory")) { // v1 平铺 → 视为 1 号魂
            NbtCompound slot = new NbtCompound();
            slot.put("Memory", root.getCompound("Memory"));
            if (root.contains("Knowledge")) slot.put("Knowledge", root.getCompound("Knowledge"));
            if (root.contains("Name")) slot.putString("Name", root.getString("Name"));
            slot.putLong("DaysSnapshot", root.getLong("DaysSnapshot"));
            NbtCompound frends = new NbtCompound();
            frends.put("1", slot);
            NbtCompound v2 = new NbtCompound();
            v2.putInt("FormatV", 2);
            v2.put("Frends", frends);
            if (root.contains("LastSeenMillis")) v2.putLong("LastSeenMillis", root.getLong("LastSeenMillis"));
            return v2;
        }
        return root;
    }

    /** v0.27 取某一号魂;没有返回 null。slot≤0 视为 1(老实体兼容)。 */
    public static NbtCompound loadSlot(UUID owner, int slot) {
        NbtCompound root = loadRoot(owner);
        if (root == null || !root.contains("Frends")) return null;
        NbtCompound frends = root.getCompound("Frends");
        String k = String.valueOf(Math.max(1, slot));
        return frends.contains(k) ? frends.getCompound(k) : null;
    }

    /** v0.27 召唤分魂:最小的"已存档且未附体"槽位(老朋友优先召回);都在场/没档 → 开新号。 */
    public static int pickSlot(UUID owner, java.util.Set<Integer> embodied) {
        int max = 0;
        NbtCompound root = loadRoot(owner);
        if (root != null && root.contains("Frends")) {
            java.util.List<Integer> stored = new java.util.ArrayList<>();
            for (String k : root.getCompound("Frends").getKeys()) {
                try { stored.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) {}
            }
            java.util.Collections.sort(stored);
            for (int slot : stored) {
                if (!embodied.contains(slot)) return slot;
                if (slot > max) max = slot;
            }
        }
        int cand = max + 1;
        while (embodied.contains(cand)) cand++;
        return cand;
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

    /** v0.21 测试面板调试钩:模拟"你离线了 n 天刚回来"——直接压待问候表,frend 见到你就说重逢话。 */
    public static void debugQueueReunion(UUID owner, long days) {
        PENDING_REUNION.put(owner, Math.max(0, days));
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
