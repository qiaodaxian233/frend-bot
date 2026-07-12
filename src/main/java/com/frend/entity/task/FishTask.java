package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/**
 * v0.20 钓鱼:朋友陪你发呆的活。<b>模拟竿</b>——原版 FishingBobberEntity 构造上绑死玩家,
 * 非玩家实体用不了真浮漂(记录于 DEVLOG),所以:真的走到水边、真的拿竿、真的等咬钩
 * (10~30 秒随机,原版手感)、竿真掉耐久;只有浮漂本体是省略的。
 *
 * <p>渔获概率照抄原版口径:85% 鱼(鳕鱼 60 / 鲑鱼 25 / 河豚 13 / 热带鱼 2),
 * 10% 垃圾(骨头/皮革/碗/线/棍),5% 小宝贝(收着做:鹦鹉螺壳/命名牌/鞍——
 * 不给附魔书,模拟竿钓出附魔书像作弊,刻意收敛,记录在案)。
 *
 * <p>规矩:必须有鱼竿(背包里);竿钓断了收工;被打了立刻收竿("鱼不咬了……先解决眼前这个!");
 * 一竿之约钓满 fishMaxCatches 条收工。等鱼时偶尔念叨一句,不刷屏。
 */
public class FishTask extends FrendTask {

    private enum Phase { FIND_WATER, GO, CAST, WAIT }

    private Phase phase = Phase.FIND_WATER;
    private BlockPos waterPos = null;
    private int waitTicks = 0;
    private int waitTarget = 0;
    private int caught = 0;
    private boolean saidIdleLine = false;

    public FishTask(FrendEntity frend) {
        super(frend);
    }

    @Override
    public String name() { return "钓鱼"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        // 挨打了就收竿——命比鱼要紧,战斗交给 CombatGoal
        if (frend.hurtTime > 0) {
            frend.say("鱼不咬了……先解决眼前这个!");
            return false;
        }

        int rodSlot = findRod();
        if (rodSlot < 0) {
            frend.say(caught == 0 ? "没鱼竿钓什么鱼……给我一根呗(放我背包里)。"
                                  : "竿断了……就钓到这儿吧,一共 " + caught + " 条。");
            return false;
        }

        switch (phase) {
            case FIND_WATER -> {
                waterPos = findWater(cfg);
                if (waterPos == null) {
                    frend.say("附近没水……带我去河边湖边再喊我。");
                    return false;
                }
                phase = Phase.GO;
            }
            case GO -> {
                if (moveNear(waterPos, 4.0)) {
                    holdRod(rodSlot);
                    phase = Phase.CAST;
                } else if (stuckTicks() > 20 * 10) {
                    frend.say("水边过不去……先算了。");
                    return false;
                }
            }
            case CAST -> {
                frend.getNavigation().stop();
                frend.getLookControl().lookAt(waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
                frend.swingHand(Hand.MAIN_HAND, true);
                // 【待编译验证】甩竿音效名;报错换 ENTITY_FISHING_BOBBER_THROW 的实际 Yarn 名或删掉
                frend.getWorld().playSound(null, frend.getBlockPos(),
                        SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.NEUTRAL, 0.5f, 1.0f);
                waitTicks = 0;
                waitTarget = 20 * (cfg.fishWaitMinSeconds
                        + frend.getRandom().nextInt(Math.max(1, cfg.fishWaitMaxSeconds - cfg.fishWaitMinSeconds + 1)));
                saidIdleLine = false;
                phase = Phase.WAIT;
            }
            case WAIT -> {
                frend.getNavigation().stop();
                frend.getLookControl().lookAt(waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
                waitTicks++;
                if (!saidIdleLine && waitTicks == waitTarget / 2 && frend.getRandom().nextFloat() < 0.5f) {
                    saidIdleLine = true;
                    frend.sayDelayed(frend.getRandom().nextBoolean()
                            ? "嘘……别惊了鱼。" : "钓鱼这事,一半钓鱼,一半发呆。");
                }
                if (waitTicks >= waitTarget) {
                    onBite(rodSlot);
                    if (caught >= cfg.fishMaxCatches) {
                        frend.say("钓了 " + caught + " 条,见好就收——今晚加餐!");
                        return false;
                    }
                    phase = Phase.CAST; // 再来一竿
                }
            }
        }
        return true;
    }

    /** 咬钩:收线动作 + 水花声 + 按原版概率给渔获 + 竿掉一点耐久。 */
    private void onBite(int rodSlot) {
        frend.swingHand(Hand.MAIN_HAND, true);
        frend.getWorld().playSound(null, waterPos,
                SoundEvents.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 0.6f, 1.0f);

        ItemStack loot = rollLoot();
        caught++;
        frend.getKnowledge().recordFish(); // v0.21 销 v0.19 挂账:钓鱼进见识
        ItemStack rest = frend.getInventory().addStack(loot.copy());
        if (!rest.isEmpty()) frend.dropStack(rest);
        if (caught == 1 || frend.getRandom().nextFloat() < 0.3f) {
            frend.sayDelayed("咬钩了!" + loot.getName().getString() + "一条,进包。");
        }

        // 竿掉耐久(手动 setDamage,同 damageTool 的理由:ItemStack#damage 签名多变)。
        // 甩竿后竿在主手,rodSlot 是 -2(主手标记),不能当背包下标用——主手优先。
        ItemStack rod = frend.getMainHandStack();
        if (!rod.isOf(Items.FISHING_ROD) && rodSlot >= 0) rod = frend.getInventory().getStack(rodSlot);
        if (rod.isOf(Items.FISHING_ROD) && rod.isDamageable()) {
            rod.setDamage(rod.getDamage() + 1);
            if (rod.getDamage() >= rod.getMaxDamage()) {
                rod.setCount(0);
                frend.getWorld().playSound(null, frend.getBlockPos(),
                        SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.NEUTRAL, 0.8f, 1.0f);
            }
        }
    }

    /** 原版口径的渔获表(85 鱼 / 10 垃圾 / 5 小宝贝)。 */
    private ItemStack rollLoot() {
        float r = frend.getRandom().nextFloat();
        if (r < 0.85f) {
            float f = frend.getRandom().nextFloat();
            if (f < 0.60f) return new ItemStack(Items.COD);
            if (f < 0.85f) return new ItemStack(Items.SALMON);
            if (f < 0.98f) return new ItemStack(Items.PUFFERFISH);
            return new ItemStack(Items.TROPICAL_FISH);
        }
        if (r < 0.95f) {
            return switch (frend.getRandom().nextInt(5)) {
                case 0 -> new ItemStack(Items.BONE);
                case 1 -> new ItemStack(Items.LEATHER);
                case 2 -> new ItemStack(Items.BOWL);
                case 3 -> new ItemStack(Items.STRING);
                default -> new ItemStack(Items.STICK);
            };
        }
        return switch (frend.getRandom().nextInt(3)) {
            case 0 -> new ItemStack(Items.NAUTILUS_SHELL);
            case 1 -> new ItemStack(Items.NAME_TAG);
            default -> new ItemStack(Items.SADDLE);
        };
    }

    /** 把鱼竿拿到主手(对调不覆盖,同 autoEquipBestWeapon 的规矩)。 */
    private void holdRod(int slot) {
        if (frend.getMainHandStack().isOf(Items.FISHING_ROD)) return;
        ItemStack rod = frend.getInventory().getStack(slot);
        ItemStack old = frend.getMainHandStack();
        frend.setStackInHand(Hand.MAIN_HAND, rod.copy());
        frend.getInventory().setStack(slot, old.isEmpty() ? ItemStack.EMPTY : old.copy());
    }

    /** 背包或主手里找一根没断的鱼竿;找不到返回 -1(主手有算 -2)。 */
    private int findRod() {
        if (frend.getMainHandStack().isOf(Items.FISHING_ROD)) return -2;
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(Items.FISHING_ROD)) return i;
        }
        return -1;
    }

    /** 找一片露天水面(水源 + 头顶是空气,别对着一格水坑甩竿)。 */
    private BlockPos findWater(FrendConfig cfg) {
        BlockPos me = frend.getBlockPos();
        int r = (int) cfg.workSearchRadius;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(me.add(-r, -4, -r), me.add(r, 2, r))) {
            if (!frend.getWorld().getFluidState(p).isIn(FluidTags.WATER)) continue;
            if (!frend.getWorld().getBlockState(p.up()).isAir()) continue;
            double d = me.getSquaredDistance(p);
            if (d < bestD) { bestD = d; best = p.toImmutable(); }
        }
        return best;
    }
}
