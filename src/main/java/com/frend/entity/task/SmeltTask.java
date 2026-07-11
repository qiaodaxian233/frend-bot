package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import java.util.function.Predicate;

/**
 * v0.20 铁器链:开炉烧矿。<b>熔炉是真的</b>——不是虚拟换算:把生铁和燃料装进真实的熔炉方块,
 * 炉火真点、原版火候(10 秒一个),烧好了从产出槽取走。路过能看见炉子在冒火,烧一半喊收工,
 * 炉里正烧的那点就留在炉里(不回滚不吞),下次接着来取。
 *
 * <p>流程:找炉子(半径 workSearchRadius)→ 没有且有 8 圆石就<b>自己盘一个</b>摆下 →
 * 走到炉边装料(生铁/生金/生铜;没煤但有木头就先烧一炉<b>木炭</b>救急,火把链闭环)→
 * 守着等火候(偶尔念叨两句)→ 出炉入包 → 清空炉子(自己装的料一点不留,还炉于民)。
 *
 * <p><b>不动别人的炉子</b>:炉子的输入/产出槽里已经有东西 → "有人在用",跳过找下一个,
 * 一个能用的都没有且没圆石就如实收工——朋友不掀别人的锅。
 *
 * <p>燃料换算(诚实向):煤/木炭一个顶 8 件(原版);没煤用木板,原版 1 板顶 1.5 件,
 * 这里按 2 板顶 3 件向上取整——宁多塞一根,不让火半路灭。
 */
public class SmeltTask extends FrendTask {

    private enum Phase { FIND, GO, LOAD, WAIT, COLLECT }

    /** 原料 → 产物名(汇报用)。生铁优先——咱开炉就是为了铁器。 */
    private static final Item[] RAW_ORDER = { Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER };

    private Phase phase = Phase.FIND;
    private BlockPos furnacePos = null;
    private int loaded = 0;          // 本炉装了几件原料
    private int waitTicks = 0;       // 等火候计时(超时保险丝)
    private int chatterCooldown = 0; // 守炉念叨去重
    private boolean placedOwn = false;
    private boolean charcoalMode = false; // 没煤烧木炭救急

    public SmeltTask(FrendEntity frend) {
        super(frend);
    }

    @Override
    public String name() { return charcoalMode ? "烧木炭" : "开炉烧矿"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();
        if (chatterCooldown > 0) chatterCooldown--;

        switch (phase) {
            case FIND -> {
                furnacePos = findUsableFurnace(cfg);
                if (furnacePos == null) {
                    // 盘一个:8 圆石,摆在脚边一格实地上
                    SimpleInventory inv = frend.getInventory();
                    if (count(inv, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) >= 8) {
                        BlockPos spot = findPlaceSpot();
                        if (spot != null) {
                            take(inv, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE), 8);
                            frend.getWorld().setBlockState(spot, Blocks.FURNACE.getDefaultState());
                            frend.swingHand(Hand.MAIN_HAND, true);
                            furnacePos = spot;
                            placedOwn = true;
                            frend.say("没炉子?咱自己盘一个。");
                        }
                    }
                }
                if (furnacePos == null) {
                    frend.say("附近没有空着的炉子,圆石也不够盘一个(要 8 块)……先算了。");
                    return false;
                }
                phase = Phase.GO;
            }
            case GO -> {
                if (moveNear(furnacePos, FrendConfig.get().workReach)) {
                    phase = Phase.LOAD;
                } else if (stuckTicks() > 20 * 10) {
                    frend.say("炉子那儿过不去……先算了。");
                    return false;
                }
            }
            case LOAD -> {
                if (!(frend.getWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity fur)) {
                    frend.say("咦,炉子没了?那还烧个啥。");
                    return false;
                }
                if (!loadFurnace(fur, cfg)) {
                    frend.say(charcoalMode
                            ? "木头不够烧炭……先算了。"
                            : "没生铁没燃料,开不了炉——去挖点铁矿回来再喊我。");
                    return false;
                }
                frend.swingHand(Hand.MAIN_HAND, true);
                frend.say(charcoalMode
                        ? "没煤就先烧一炉木炭救急——火把也指着它呢。"
                        : "装炉!" + loaded + " 件下去了,等火候吧——原版火候,急不来。");
                waitTicks = 0;
                phase = Phase.WAIT;
            }
            case WAIT -> {
                frend.getNavigation().stop();
                frend.getLookControl().lookAt(furnacePos.getX() + 0.5, furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
                if (!(frend.getWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity fur)) {
                    frend.say("炉子没了?!谁拆的……算了算了。");
                    return false;
                }
                waitTicks++;
                if (chatterCooldown <= 0 && frend.getRandom().nextFloat() < 0.004f) {
                    chatterCooldown = 20 * 45;
                    frend.sayDelayed(frend.getRandom().nextBoolean()
                            ? "火候正好,等着就行。" : "守炉子这活……适合发呆。");
                }
                boolean inputEmpty = fur.getStack(0).isEmpty();
                boolean timeout = waitTicks > 200 * loaded + 600; // 原版 200t/件 + 富余;火灭了别站一辈子
                if (inputEmpty || timeout) {
                    phase = Phase.COLLECT;
                }
            }
            case COLLECT -> {
                if (frend.getWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity fur) {
                    ItemStack out = fur.getStack(2);
                    int got = out.getCount();
                    String what = got > 0 ? out.getName().getString() : null;
                    if (got > 0) {
                        giveToFrend(out.copy());
                        fur.setStack(2, ItemStack.EMPTY);
                    }
                    // 还炉于民:自己装的余料/余燃全部收回,不在别人炉里留渣
                    reclaim(fur, 0);
                    reclaim(fur, 1);
                    fur.markDirty();
                    if (got > 0) {
                        frend.say("出炉!" + got + " 个" + what + "到手" + (charcoalMode ? ",火把有着落了。" : ",铁家伙不远了。"));
                    } else {
                        frend.say("这炉没烧出东西……火没顶住,料我收回来了。");
                    }
                }
                return false;
            }
        }
        return true;
    }

    // ===================== 装炉 =====================

    /** 往真熔炉里装原料 + 燃料;没得装返回 false。 */
    private boolean loadFurnace(AbstractFurnaceBlockEntity fur, FrendConfig cfg) {
        SimpleInventory inv = frend.getInventory();

        // 选原料:生铁 > 生金 > 生铜;都没有但有木头且缺煤 → 烧木炭救急
        Item raw = null;
        for (Item cand : RAW_ORDER) {
            if (count(inv, s -> s.isOf(cand)) > 0) { raw = cand; break; }
        }
        int coal = count(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL));
        if (raw == null) {
            if (coal == 0 && count(inv, s -> s.isIn(ItemTags.LOGS)) >= 2) {
                charcoalMode = true;
            } else {
                return false;
            }
        }

        int batch;
        if (charcoalMode) {
            // 原木进炉产木炭;留 1 根原木劈板当燃料的火种
            batch = Math.min(cfg.smeltBatchMax, count(inv, s -> s.isIn(ItemTags.LOGS)) - 1);
            if (batch < 1) return false;
            ItemStack in = new ItemStack(Items.OAK_LOG, batch); // 简化:炉里统一按橡木记(产物都是木炭,无伤)
            take(inv, s -> s.isIn(ItemTags.LOGS), batch);
            fur.setStack(0, in);
        } else {
            final Item rawF = raw;
            batch = Math.min(cfg.smeltBatchMax, count(inv, s -> s.isOf(rawF)));
            take(inv, s -> s.isOf(rawF), batch);
            fur.setStack(0, new ItemStack(raw, batch));
        }
        loaded = batch;

        // 燃料:炉子燃料槽只有一格,只能装一种——煤(1 顶 8)优先,不够就全上木板(2 板顶 3);
        // 两样都罩不住整批就<b>按火力缩批</b>,多出的原料退回包里,绝不让生铁锁死在冷炉里。
        int coalCap = coal * 8;
        int planksHave = count(inv, s -> s.isIn(ItemTags.PLANKS)) + count(inv, s -> s.isIn(ItemTags.LOGS)) * 4
                - (charcoalMode ? 4 : 0); // 烧炭模式原木是原料,只留劈板余量
        int planksCap = Math.max(0, planksHave) * 3 / 2;
        if (coalCap >= batch) {
            int coalUse = (batch + 7) / 8;
            take(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL), coalUse);
            fur.setStack(1, new ItemStack(Items.COAL, coalUse)); // 简化:炉内燃料统一按煤记
        } else if (planksCap >= 1) {
            if (planksCap < batch) { // 火力不够,缩批退料
                int back = batch - planksCap;
                ItemStack in = fur.getStack(0);
                in.decrement(back);
                giveToFrend(new ItemStack(in.getItem(), back));
                batch = planksCap;
                loaded = batch;
            }
            int planksUse = (batch * 2 + 2) / 3;
            while (count(inv, s -> s.isIn(ItemTags.PLANKS)) < planksUse
                    && count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1) {
                take(inv, s -> s.isIn(ItemTags.LOGS), 1);
                inv.addStack(new ItemStack(Items.OAK_PLANKS, 4));
            }
            planksUse = Math.min(planksUse, count(inv, s -> s.isIn(ItemTags.PLANKS)));
            take(inv, s -> s.isIn(ItemTags.PLANKS), planksUse);
            fur.setStack(1, new ItemStack(Items.OAK_PLANKS, planksUse));
        } else {
            // 一点燃料都凑不出:料退回来
            reclaim(fur, 0);
            return false;
        }
        fur.markDirty();
        return true;
    }

    /** 把炉子某槽的东西收回背包(还炉于民/收残料)。 */
    private void reclaim(AbstractFurnaceBlockEntity fur, int slot) {
        ItemStack s = fur.getStack(slot);
        if (!s.isEmpty()) {
            giveToFrend(s.copy());
            fur.setStack(slot, ItemStack.EMPTY);
        }
    }

    private void giveToFrend(ItemStack stack) {
        ItemStack rest = frend.getInventory().addStack(stack);
        if (!rest.isEmpty()) frend.dropStack(rest); // 包满落地,不吞
    }

    // ===================== 找炉/盘炉 =====================

    /** 半径内找一个"没人在用"的熔炉(输入/产出槽都空;燃料槽有剩煤不算占用)。 */
    private BlockPos findUsableFurnace(FrendConfig cfg) {
        BlockPos me = frend.getBlockPos();
        int r = (int) cfg.workSearchRadius;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(me.add(-r, -3, -r), me.add(r, 3, r))) {
            if (!frend.getWorld().getBlockState(p).isOf(Blocks.FURNACE)) continue;
            if (!(frend.getWorld().getBlockEntity(p) instanceof AbstractFurnaceBlockEntity fur)) continue;
            if (!fur.getStack(0).isEmpty() || !fur.getStack(2).isEmpty()) continue; // 有人在用,不掀别人的锅
            double d = me.getSquaredDistance(p);
            if (d < bestD) { bestD = d; best = p.toImmutable(); }
        }
        return best;
    }

    /** 脚边找一格能摆炉子的实地(空气 + 下方实心,不摆头顶不摆水里)。 */
    private BlockPos findPlaceSpot() {
        BlockPos me = frend.getBlockPos();
        for (BlockPos p : BlockPos.iterate(me.add(-2, -1, -2), me.add(2, 1, 2))) {
            if (p.equals(me) || p.equals(me.up())) continue; // 别把自己盘进炉子里
            if (!frend.getWorld().getBlockState(p).isAir()) continue;
            if (!frend.getWorld().getBlockState(p.down()).isSolidBlock(frend.getWorld(), p.down())) continue;
            if (!frend.getWorld().getFluidState(p).isEmpty()) continue;
            return p.toImmutable();
        }
        return null;
    }

    // ===================== 给自主决策用的静态判断 =====================

    /** 攒了一把生矿且燃料链有戏(炉子的事任务自己解决:找/盘/如实收工)。 */
    public static boolean shouldSmelt(FrendEntity f) {
        SimpleInventory inv = f.getInventory();
        int raws = 0;
        for (Item cand : RAW_ORDER) {
            final Item c = cand;
            raws += count(inv, s -> s.isOf(c));
        }
        if (raws < 4) return false; // 攒够半炉再开火,别为两块生铁折腾
        return count(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) >= 1
                || count(inv, s -> s.isIn(ItemTags.PLANKS)) >= 4
                || count(inv, s -> s.isIn(ItemTags.LOGS)) >= 2;
    }

    // ===================== 库存小工具(与 CraftTask 同款) =====================

    private static int count(SimpleInventory inv, Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && p.test(s)) n += s.getCount();
        }
        return n;
    }

    private static void take(SimpleInventory inv, Predicate<ItemStack> p, int n) {
        for (int i = 0; i < inv.size() && n > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !p.test(s)) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
        }
    }
}
