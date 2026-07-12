package com.frend.system;

import com.frend.entity.FrendEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.27 多 frend 协作:目标认领登记处——"你砍那棵,我砍这棵",分工靠认领不靠指挥。
 *
 * <p>设计:极简共享账本。任务选中目标时登记(claim),别人找目标时跳过已认领的
 * (claimedByOther),任务结束/被打断由 FrendEntity 统一清账(releaseAll)。
 * <b>过期兜底</b>:认领 60 秒没续(崩服/实体卸载)视为无主——宁可偶尔撞车,不许永久死锁。
 * 键带维度(主世界和下界坐标会重合)。不落盘:重启后大家重新认领,天然自愈。
 */
public final class FrendCrew {

    private record Claim(UUID frend, long millis) {}

    private static final Map<String, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static final long STALE_MS = 60_000;

    private FrendCrew() {}

    private static String key(FrendEntity f, BlockPos pos) {
        return f.getWorld().getRegistryKey().getValue() + "|" + pos.asLong();
    }

    /** 认领(重复认领 = 续期)。 */
    public static void claim(FrendEntity f, BlockPos pos) {
        CLAIMS.put(key(f, pos), new Claim(f.getUuid(), System.currentTimeMillis()));
    }

    /** 这块被别的 frend 认领着(且没过期)?——找目标时跳过它,别抢同伴的活。 */
    public static boolean claimedByOther(FrendEntity f, BlockPos pos) {
        Claim c = CLAIMS.get(key(f, pos));
        if (c == null) return false;
        if (System.currentTimeMillis() - c.millis > STALE_MS) return false; // 过期视无主
        return !c.frend.equals(f.getUuid());
    }

    /** 释放一块(只释放自己的)。 */
    public static void release(FrendEntity f, BlockPos pos) {
        String k = key(f, pos);
        Claim c = CLAIMS.get(k);
        if (c != null && c.frend.equals(f.getUuid())) CLAIMS.remove(k);
    }

    /** 清掉这只 frend 的全部认领(任务结束/被打断/死亡时,FrendEntity 统一调)。 */
    public static void releaseAll(FrendEntity f) {
        CLAIMS.entrySet().removeIf(e -> e.getValue().frend.equals(f.getUuid()));
    }

    /** 附近有同主人的伙伴在干同一种活?(开工搭话用:"那片归你,这片归我!") */
    public static boolean crewmateNearbyDoing(FrendEntity f, String taskName, double radius) {
        if (taskName == null || f.getOwnerUuid() == null) return false;
        return !f.getWorld().getEntitiesByClass(FrendEntity.class,
                f.getBoundingBox().expand(radius),
                e -> e != f && e.isAlive()
                        && f.getOwnerUuid().equals(e.getOwnerUuid())
                        && taskName.equals(e.currentTaskName())).isEmpty();
    }
}
