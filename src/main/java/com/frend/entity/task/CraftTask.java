package com.frend.entity.task;

import com.frend.entity.FrendEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * v0.16 全智能自给自足:自己合成工具/火把,不再"没镐伸手要"。
 *
 * <p><b>虚拟合成</b>:按原版配比直接换算背包材料(1 原木→4 木板;2 木板→4 木棍;3 圆石+2 棍→石镐;
 * 1 煤+1 棍→4 火把……)。<b>刻意简化</b>:不要求工作台——像玩家"会做"这件事,省掉找台/放台的
 * 整条状态机(记录于 DEVLOG,以后想较真再补)。木板统一产出橡木板(不跟踪木种,同记录)。
 *
 * <p><b>不瞬间变</b>:每 25 tick 才完成一步转换,站桩、周期挥手,像人在案前鼓捣;
 * 每一步都是真实材料进出,喊"收工"打断时做了几步就是几步,不回滚不白嫖。
 *
 * <p>目标:
 * <ul>
 *   <li><b>TOOLS</b>:按 镐→斧→剑 补齐缺的工具;有 3 圆石优先石器,没有就先做木器
 *       (木镐是自举的钥匙:撸树→木镐→凿石→石器);</li>
 *   <li><b>TORCHES</b>:煤/木炭 + 棍 → 火把,补到 32 根收手。</li>
 * </ul>
 */
public class CraftTask extends FrendTask {

    public enum Goal { TOOLS, TORCHES }

    private static final int CRAFT_INTERVAL = 25; // 每步 1.25s,不是魔术
    private static final int TORCH_TARGET = 32;

    private final Goal goal;
    private int timer = 0;
    private final List<String> crafted = new ArrayList<>();

    public CraftTask(FrendEntity frend, Goal goal) {
        super(frend);
        this.goal = goal;
    }

    @Override
    public String name() { return goal == Goal.TOOLS ? "打造工具" : "搓火把"; }

    @Override
    public boolean tick() {
        frend.getNavigation().stop(); // 站着干细活
        if (++timer < CRAFT_INTERVAL) {
            if (timer % 8 == 0) frend.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        timer = 0;
        boolean did = goal == Goal.TOOLS ? stepTools() : stepTorches();
        if (!did) {
            frend.say(crafted.isEmpty()
                    ? "材料不凑手,巧妇难为无米之炊……先这样。"
                    : "搞定!" + String.join("、", crafted) + "出炉,家伙齐了。");
            return false;
        }
        return true;
    }

    // ===================== 每步转换 =====================

    /** 做一步"补齐工具"的转换;没有可做的下一步返回 false。 */
    private boolean stepTools() {
        SimpleInventory inv = frend.getInventory();
        // 缺什么补什么,镐优先(镐是生产资料)
        if (frend.findUsableTool(ItemTags.PICKAXES).isEmpty()
                && stepCraftTool(inv, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE, "镐")) return true;
        if (frend.findUsableTool(ItemTags.AXES).isEmpty()
                && stepCraftTool(inv, Items.STONE_AXE, Items.WOODEN_AXE, "斧")) return true;
        if (frend.findUsableTool(ItemTags.SWORDS).isEmpty()
                && stepCraftTool(inv, Items.STONE_SWORD, Items.WOODEN_SWORD, "剑")) return true;
        return false;
    }

    /**
     * 朝着"做出这件工具"推进一步(材料链:原木→木板→木棍→成品)。
     * 这一步做了任何转换都返回 true;这件工具的链条走不通返回 false(调用方试下一件)。
     */
    private boolean stepCraftTool(SimpleInventory inv, Item stoneTier, Item woodTier, String cn) {
        boolean stone = count(inv, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) >= 3;
        int planksNeed = stone ? 0 : 3;

        // 棍不够 → 先备棍(2 板→4 棍);板不够 → 先劈板(1 原木→4 板)
        if (count(inv, s -> s.isOf(Items.STICK)) < 2) {
            if (count(inv, s -> s.isIn(ItemTags.PLANKS)) >= 2) {
                take(inv, s -> s.isIn(ItemTags.PLANKS), 2);
                give(inv, new ItemStack(Items.STICK, 4));
                return true;
            }
            if (count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1) {
                take(inv, s -> s.isIn(ItemTags.LOGS), 1);
                give(inv, new ItemStack(Items.OAK_PLANKS, 4));
                return true;
            }
            return false; // 连棍都凑不出,这件做不了
        }
        // 木器还需要 3 板
        if (planksNeed > 0 && count(inv, s -> s.isIn(ItemTags.PLANKS)) < planksNeed) {
            if (count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1) {
                take(inv, s -> s.isIn(ItemTags.LOGS), 1);
                give(inv, new ItemStack(Items.OAK_PLANKS, 4));
                return true;
            }
            return false;
        }
        // 成品
        if (stone) {
            take(inv, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE), 3);
            take(inv, s -> s.isOf(Items.STICK), 2);
            give(inv, new ItemStack(stoneTier));
            crafted.add("石" + cn);
        } else {
            take(inv, s -> s.isIn(ItemTags.PLANKS), 3);
            take(inv, s -> s.isOf(Items.STICK), 2);
            give(inv, new ItemStack(woodTier));
            crafted.add("木" + cn);
        }
        return true;
    }

    /** 做一步"搓火把"的转换。 */
    private boolean stepTorches() {
        SimpleInventory inv = frend.getInventory();
        if (count(inv, s -> s.isOf(Items.TORCH)) >= TORCH_TARGET) return false;
        if (count(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) < 1) return false;
        if (count(inv, s -> s.isOf(Items.STICK)) < 1) {
            if (count(inv, s -> s.isIn(ItemTags.PLANKS)) >= 2) {
                take(inv, s -> s.isIn(ItemTags.PLANKS), 2);
                give(inv, new ItemStack(Items.STICK, 4));
                return true;
            }
            if (count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1) {
                take(inv, s -> s.isIn(ItemTags.LOGS), 1);
                give(inv, new ItemStack(Items.OAK_PLANKS, 4));
                return true;
            }
            return false;
        }
        take(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL), 1);
        take(inv, s -> s.isOf(Items.STICK), 1);
        give(inv, new ItemStack(Items.TORCH, 4));
        if (!crafted.contains("火把")) crafted.add("火把");
        return true;
    }

    // ===================== 给自主决策用的静态判断 =====================

    /** 缺工具且材料链走得通(粗判,做不完任务会自己如实收工)。 */
    public static boolean shouldCraftTools(FrendEntity f) {
        boolean missing = f.findUsableTool(ItemTags.PICKAXES).isEmpty()
                || f.findUsableTool(ItemTags.AXES).isEmpty()
                || f.findUsableTool(ItemTags.SWORDS).isEmpty();
        if (!missing) return false;
        SimpleInventory inv = f.getInventory();
        int logs = count(inv, s -> s.isIn(ItemTags.LOGS));
        int planks = count(inv, s -> s.isIn(ItemTags.PLANKS));
        int sticks = count(inv, s -> s.isOf(Items.STICK));
        // 木头当量够出一件最小的木器(3 板+2 棍 ≈ 2 原木)就值得开工
        return logs * 4 + planks + sticks >= 5 && (logs > 0 || planks >= 2 || sticks >= 2);
    }

    /** 火把见底且有煤有木头链。 */
    public static boolean shouldCraftTorches(FrendEntity f) {
        SimpleInventory inv = f.getInventory();
        if (count(inv, s -> s.isOf(Items.TORCH)) >= 8) return false;
        if (count(inv, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) < 1) return false;
        return count(inv, s -> s.isOf(Items.STICK)) >= 1
                || count(inv, s -> s.isIn(ItemTags.PLANKS)) >= 2
                || count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1;
    }

    // ===================== 库存小工具 =====================

    private static int count(SimpleInventory inv, Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && p.test(s)) n += s.getCount();
        }
        return n;
    }

    /** 按谓词扣除 n 个(跨堆);材料在 count 校验之后才 take,不会扣穿。 */
    private static void take(SimpleInventory inv, Predicate<ItemStack> p, int n) {
        for (int i = 0; i < inv.size() && n > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !p.test(s)) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
        }
    }

    private void give(SimpleInventory inv, ItemStack stack) {
        ItemStack rest = inv.addStack(stack);
        if (!rest.isEmpty()) frend.dropStack(rest); // 包满落地,不吞
    }
}
