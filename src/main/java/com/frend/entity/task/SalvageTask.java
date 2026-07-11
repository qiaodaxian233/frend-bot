package com.frend.entity.task;

import com.frend.entity.FrendEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * v0.15 帮你捡尸:你倒下了,它赶到出事地点把掉落物全收进"遗物袋"(独立于自己背包,
 * 存箱子不会把你的遗物存进去),收完转跟随模式给你送去——见面全数奉还(FrendEntity 侧)。
 *
 * <p>规矩:
 * <ul>
 *   <li>只收<b>掉落物实体</b>,不碰箱子不碰别人;经验球不收(捡了也还不回去,不装好人);</li>
 *   <li>连续 4 秒扫不到新掉落 → 收工(该捡的捡完了);总时长 90 秒兜底(别守着虚空发呆);</li>
 *   <li>走不到出事地点(掉岩浆里/虚空)→ 如实说,不逞能。</li>
 * </ul>
 */
public class SalvageTask extends FrendTask {

    /** 出事地点。 */
    private final BlockPos spot;
    private int quietTicks = 0;  // 连续没东西可捡的 tick
    private int totalTicks = 0;
    private int collected = 0;

    public SalvageTask(FrendEntity frend, BlockPos spot) {
        super(frend);
        this.spot = spot.toImmutable();
    }

    @Override
    public String name() { return "捡装备"; }

    @Override
    public boolean tick() {
        totalTicks++;
        if (totalTicks > 20 * 90) return finish(); // 90s 兜底

        // 先赶到出事地点附近
        if (frend.squaredDistanceTo(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5) > 6 * 6) {
            if (!moveNear(spot, 5.0) && stuckTicks() > 20 * 12) {
                frend.say("你出事的地方我过不去……东西可能保不住了,对不住。");
                frend.setMode(FrendEntity.Mode.FOLLOW);
                return false;
            }
            return true;
        }

        // 到场:扫 8 格内的掉落物,逐个收进遗物袋
        List<ItemEntity> items = frend.getWorld().getEntitiesByClass(
                ItemEntity.class, new Box(spot).expand(8), e -> e.isAlive() && !e.getStack().isEmpty());
        if (items.isEmpty()) {
            quietTicks++;
            return quietTicks <= 20 * 4 || finish(); // 静默 4 秒 = 捡干净了
        }
        quietTicks = 0;
        ItemEntity nearest = items.get(0);
        double best = Double.MAX_VALUE;
        for (ItemEntity it : items) {
            double d = frend.squaredDistanceTo(it);
            if (d < best) { best = d; nearest = it; }
        }
        if (best > 2.0 * 2.0) {
            moveNear(nearest.getBlockPos(), 1.6);
            if (stuckTicks() > 20 * 8) { nearest.discard(); resetStuck(); } // 卡沟里的那件够不着,认了
            return true;
        }
        // 收入遗物袋(袋满的余量留在地上,别硬吞)
        net.minecraft.item.ItemStack rest = frend.addSalvage(nearest.getStack());
        if (rest.isEmpty()) nearest.discard();
        else nearest.setStack(rest);
        collected++;
        resetStuck();
        return true;
    }

    private boolean finish() {
        if (collected > 0) {
            frend.say("东西都收好了(" + collected + " 摊),一样没落,我给你送去!");
            frend.getMemory().record(frend.getWorld().getTime(), "你倒下那次,我把东西全捡了回来");
            frend.setMode(FrendEntity.Mode.FOLLOW); // 收完不傻站,主动送货上门
        } else {
            frend.say("到地方了,可没找着掉落……要么被清了,要么滚沟里了,你别怪我。");
            frend.setMode(FrendEntity.Mode.FOLLOW);
        }
        return false;
    }
}
