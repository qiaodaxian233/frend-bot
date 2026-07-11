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
 *   <li><b>TOOLS</b>(v0.20 档位制):镐→斧→剑逐类看"手里最好的一档 vs 材料能出的最高档",
 *       能更好就打更好的——缺了补、有铁锭升铁器、<b>不等报废主动换代</b>;木 0/石 1/铁 2,
 *       金按石算、钻石以上不折腾。木镐仍是自举的钥匙:撸树→木镐→凿石→石器→(烧铁)→铁器;</li>
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

    /**
     * v0.20 档位制:木 0 / 石 1 / 铁 2(金器脆,按石器算;钻石以上 3,不折腾)。
     * 每类工具比较"手里最好的一档"vs"材料能出的最高档",能更好就打一件更好的——
     * <b>主动升级,不等报废</b>。旧的不扔:留作备用,回头存箱子自然清走。
     */
    private boolean stepTools() {
        SimpleInventory inv = frend.getInventory();
        if (stepToolType(inv, ItemTags.PICKAXES, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE, "镐")) return true;
        if (stepToolType(inv, ItemTags.AXES,     Items.IRON_AXE,     Items.STONE_AXE,     Items.WOODEN_AXE,     "斧")) return true;
        if (stepToolType(inv, ItemTags.SWORDS,   Items.IRON_SWORD,   Items.STONE_SWORD,   Items.WOODEN_SWORD,   "剑")) return true;
        return false;
    }

    /**
     * 朝着"这一类工具打一件更好的"推进一步(材料链:原木→木板→木棍→成品)。
     * 做了任何转换返回 true;这一类不需要/走不通返回 false(调用方看下一类)。
     * 配比刻意统一 3 材 + 2 棍(剑原版是 2 材 1 棍,这里多收一点,宁多勿少,记录于 DEVLOG)。
     */
    private boolean stepToolType(SimpleInventory inv, net.minecraft.registry.tag.TagKey<Item> tag,
                                 Item ironT, Item stoneT, Item woodT, String cn) {
        int owned = bestOwnedTier(frend, tag);
        int craftable = bestCraftableTier(inv);
        if (craftable <= owned) return false; // 手里的不比材料差,不折腾

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
            return false; // 连棍都凑不出,这类做不了
        }
        // 木器还需要 3 板
        if (craftable == 0 && count(inv, s -> s.isIn(ItemTags.PLANKS)) < 3) {
            if (count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1) {
                take(inv, s -> s.isIn(ItemTags.LOGS), 1);
                give(inv, new ItemStack(Items.OAK_PLANKS, 4));
                return true;
            }
            return false;
        }
        // 成品
        if (craftable == 2) {
            take(inv, s -> s.isOf(Items.IRON_INGOT), 3);
            take(inv, s -> s.isOf(Items.STICK), 2);
            give(inv, new ItemStack(ironT));
            crafted.add("铁" + cn);
        } else if (craftable == 1) {
            take(inv, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE), 3);
            take(inv, s -> s.isOf(Items.STICK), 2);
            give(inv, new ItemStack(stoneT));
            crafted.add("石" + cn);
        } else {
            take(inv, s -> s.isIn(ItemTags.PLANKS), 3);
            take(inv, s -> s.isOf(Items.STICK), 2);
            give(inv, new ItemStack(woodT));
            crafted.add("木" + cn);
        }
        return true;
    }

    /** 材料能出的最高档(粗判:成品材料够就算,棍链由步进函数如实处理)。 */
    private static int bestCraftableTier(SimpleInventory inv) {
        boolean stickChain = count(inv, s -> s.isOf(Items.STICK)) >= 2
                || count(inv, s -> s.isIn(ItemTags.PLANKS)) >= 2
                || count(inv, s -> s.isIn(ItemTags.LOGS)) >= 1;
        if (!stickChain) return -1;
        if (count(inv, s -> s.isOf(Items.IRON_INGOT)) >= 3) return 2;
        if (count(inv, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) >= 3) return 1;
        if (count(inv, s -> s.isIn(ItemTags.PLANKS)) + count(inv, s -> s.isIn(ItemTags.LOGS)) * 4 >= 3) return 0;
        return -1;
    }

    /** 这一类工具手里最好的一档(-1 = 一件能用的都没有;耐久见底的不算,和 findUsableTool 同口径)。 */
    static int bestOwnedTier(FrendEntity f, net.minecraft.registry.tag.TagKey<Item> tag) {
        int reserve = com.frend.FrendConfig.get().toolReserveDurability;
        int best = -1;
        ItemStack mh = f.getMainHandStack();
        if (!mh.isEmpty() && mh.isIn(tag) && usable(mh, reserve)) best = Math.max(best, tierOf(mh.getItem()));
        SimpleInventory inv = f.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isIn(tag) && usable(s, reserve)) best = Math.max(best, tierOf(s.getItem()));
        }
        return best;
    }

    private static boolean usable(ItemStack s, int reserve) {
        return !s.isDamageable() || s.getMaxDamage() - s.getDamage() > reserve;
    }

    private static int tierOf(Item i) {
        if (i == Items.WOODEN_PICKAXE || i == Items.WOODEN_AXE || i == Items.WOODEN_SWORD) return 0;
        if (i == Items.STONE_PICKAXE || i == Items.STONE_AXE || i == Items.STONE_SWORD
                || i == Items.GOLDEN_PICKAXE || i == Items.GOLDEN_AXE || i == Items.GOLDEN_SWORD) return 1; // 金器脆,当石器
        if (i == Items.IRON_PICKAXE || i == Items.IRON_AXE || i == Items.IRON_SWORD) return 2;
        return 3; // 钻石/下界合金,已经很好,不折腾
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

    /** v0.20 缺工具<b>或有升级机会</b>且材料链走得通(粗判,做不完任务会自己如实收工)。 */
    public static boolean shouldCraftTools(FrendEntity f) {
        int craftable = bestCraftableTier(f.getInventory());
        if (craftable < 0) return false;
        return craftable > bestOwnedTier(f, ItemTags.PICKAXES)
                || craftable > bestOwnedTier(f, ItemTags.AXES)
                || craftable > bestOwnedTier(f, ItemTags.SWORDS);
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
