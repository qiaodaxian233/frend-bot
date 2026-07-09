package com.frend.entity.task;

import com.frend.FrendConfig;
import com.frend.entity.FrendEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;

/**
 * 回家存箱子:走到家 → 在家附近找箱子/木桶 → 把背包倒进去。
 *
 * <p>"像人"的取舍:<b>工具和吃的自己留着</b>(镐/斧/剑/铲 + 任何食物不入库)——
 * 不然存完货就变成手无寸铁饿肚子的憨憨。装不下的也自己背着并说明。
 */
public class DepositTask extends FrendTask {

    private BlockPos chestPos = null;
    private boolean searchedChest = false;

    public DepositTask(FrendEntity frend) {
        super(frend);
    }

    @Override
    public String name() { return "回家存箱子"; }

    @Override
    public boolean tick() {
        FrendConfig cfg = FrendConfig.get();

        if (!frend.hasHome()) {
            frend.say("咱还没定过家呢……先 /frend home set 定个家,再放个箱子。");
            return false;
        }
        if (!frend.isHomeInThisDimension()) {
            frend.say("家不在这个维度,我自己走不过去,你带我回去我再存。");
            return false;
        }

        // 阶段一:走回家
        if (chestPos == null && !searchedChest) {
            if (!moveNear(frend.getHomePos(), 3.0)) {
                if (stuckTicks() > 20 * 20) { // 20 秒还没到家 → 放弃
                    frend.say("回家的路被堵住了,我过不去……你来接我一下?");
                    return false;
                }
                return true;
            }
            // 到家,找箱子
            searchedChest = true;
            chestPos = findChestNearHome();
            if (chestPos == null) {
                frend.say("到家了,但附近没箱子……在家旁边放个箱子或木桶,我才好存东西。");
                return false;
            }
        }

        // 阶段二:走到箱子旁
        if (!moveNear(chestPos, cfg.workReach)) {
            if (stuckTicks() > 20 * 8) {
                frend.say("箱子那儿我过不去……帮我清清路?");
                return false;
            }
            return true;
        }

        // 阶段三:倒货
        deposit();
        return false;
    }

    /** 家坐标 ±4 格内找箱子/陷阱箱/木桶。 */
    private BlockPos findChestNearHome() {
        BlockPos home = frend.getHomePos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(home.add(-4, -2, -4), home.add(4, 3, 4))) {
            BlockState s = frend.getWorld().getBlockState(p);
            if (!s.isOf(Blocks.CHEST) && !s.isOf(Blocks.TRAPPED_CHEST) && !s.isOf(Blocks.BARREL)) continue;
            double d = p.getSquaredDistance(home);
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }

    private void deposit() {
        if (!(frend.getWorld().getBlockEntity(chestPos) instanceof Inventory chest)) {
            frend.say("咦,箱子打不开……换个普通箱子试试?");
            return;
        }
        SimpleInventory bag = frend.getInventory();
        int movedStacks = 0;
        boolean full = false;

        for (int i = 0; i < bag.size(); i++) {
            ItemStack stack = bag.getStack(i);
            if (stack.isEmpty() || keepForSelf(stack)) continue;

            ItemStack rest = insert(chest, stack);
            if (rest.getCount() < stack.getCount()) movedStacks++;
            bag.setStack(i, rest);
            if (!rest.isEmpty()) full = true;
        }
        frend.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);

        if (movedStacks == 0) {
            frend.say(full ? "箱子满了,一点都塞不进去……再放个箱子?" : "包里没什么可存的,工具和干粮我留着。");
        } else if (full) {
            frend.say("存了 " + movedStacks + " 组,箱子满了,剩下的我先背着。");
        } else {
            frend.say("存好了," + movedStacks + " 组东西入库。工具和干粮我自己留着。");
        }
    }

    /** 自己留着的:四大工具 + 任何食物。 */
    private boolean keepForSelf(ItemStack stack) {
        if (stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.PICKAXES)
                || stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.SHOVELS)) return true;
        // 【待编译验证】1.21 数据组件:食物 = 带 FOOD 组件
        return stack.get(DataComponentTypes.FOOD) != null;
    }

    /** 往箱子里塞一组,返回塞不下的剩余。 */
    private ItemStack insert(Inventory chest, ItemStack stack) {
        ItemStack moving = stack.copy();
        // 先并入同类
        for (int i = 0; i < chest.size() && !moving.isEmpty(); i++) {
            ItemStack slot = chest.getStack(i);
            if (slot.isEmpty() || !ItemStack.areItemsAndComponentsEqual(slot, moving)) continue; // 【待编译验证】1.21 同物判断
            int can = Math.min(moving.getCount(), slot.getMaxCount() - slot.getCount());
            if (can > 0) {
                slot.increment(can);
                moving.decrement(can);
            }
        }
        // 再找空位
        for (int i = 0; i < chest.size() && !moving.isEmpty(); i++) {
            if (chest.getStack(i).isEmpty()) {
                chest.setStack(i, moving);
                moving = ItemStack.EMPTY;
            }
        }
        chest.markDirty();
        return moving;
    }
}
