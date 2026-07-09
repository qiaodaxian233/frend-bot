package com.frend.entity;

import com.frend.FrendConfig;
import com.frend.system.FrendScheduler;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * frend 本体:类玩家陪伴 NPC。
 *
 * <p>v0.1 能力:
 * <ul>
 *   <li>三种模式:FOLLOW 跟随 / STAY 停留 / GO_HOME 回家(到家自动转 STAY)</li>
 *   <li>主人绑定(UUID,NBT 持久化);家坐标(含维度,NBT 持久化)</li>
 *   <li>27 格背包:主人右键打开(原版 9×3 箱子界面,零自定义 screen);死亡/解散全掉落</li>
 *   <li>"像人"细节:低血提醒主人、偶尔闲聊、被动缓慢回血、死前留遗言,说话统一走 {@link #say}
 *       并按聊天半径广播,不刷屏(全部带冷却)</li>
 * </ul>
 *
 * <p>刻意不做的:不打怪(v0.3)、不捡物(v0.2)、不自然生成(只能 /frend summon)。
 * 模式只存服务端 + NBT,客户端渲染不依赖它,所以不需要 DataTracker。
 */
public class FrendEntity extends PathAwareEntity {

    /** 行为模式。 */
    public enum Mode { FOLLOW, STAY, GO_HOME }

    private Mode mode = Mode.FOLLOW;
    private UUID ownerUuid = null;

    /** 27 格随身背包。 */
    private final SimpleInventory inventory = new SimpleInventory(27);

    /** 家(可空);维度记 Identifier 字符串,如 minecraft:overworld。 */
    private BlockPos homePos = null;
    private String homeDimension = null;

    // ===== 说话冷却(tick,只在服务端 mobTick 递减) =====
    private int lowHealthWarnCooldown = 0;
    private int ambientCooldown = 20 * 60; // 出生一分钟内不闲聊
    private int hurtTalkCooldown = 0;

    private static final String[] AMBIENT_LINES = {
            "这边风景不错,值得盖个观景台。",
            "你说……末影人整天搬方块图什么呢?",
            "等我学会挖矿了,钻石都归你,我就要一把铁镐。",
            "走这么久了,记得吃东西啊。",
            "我记得咱家的方向,迷路了就喊我带路。",
            "晚上怪多,咱俩别走散了。"
    };

    private static final String[] HURT_LINES = {
            "嘶——疼疼疼!",
            "谁打我?!",
            "没事,小伤,继续走。"
    };

    public FrendEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.setPersistent(); // 不因玩家远离而消失
        this.setCanPickUpLoot(false);
    }

    /** 属性:数值走配置(配置在实体注册之前加载)。 */
    public static DefaultAttributeContainer.Builder createFrendAttributes() {
        FrendConfig c = FrendConfig.get();
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, c.frendMaxHealth)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, c.frendMoveSpeed)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(2, new FrendFollowOwnerGoal(this));
        this.goalSelector.add(3, new FrendGoHomeGoal(this));
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(7, new LookAroundGoal(this));
    }

    // ===================== 主人 / 模式 / 家 =====================

    public void setOwner(PlayerEntity player) { this.ownerUuid = player.getUuid(); }

    public UUID getOwnerUuid() { return ownerUuid; }

    public boolean isOwner(PlayerEntity player) {
        return ownerUuid != null && ownerUuid.equals(player.getUuid());
    }

    /** 主人(仅同维度、在线时非空)。 */
    public PlayerEntity getOwnerPlayer() {
        if (ownerUuid == null) return null;
        return this.getWorld().getPlayerByUuid(ownerUuid);
    }

    public Mode getMode() { return mode; }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.getNavigation().stop();
    }

    public BlockPos getHomePos() { return homePos; }

    public String getHomeDimension() { return homeDimension; }

    public boolean hasHome() { return homePos != null && homeDimension != null; }

    /** 家是否在当前所处维度。 */
    public boolean isHomeInThisDimension() {
        return hasHome() && this.getWorld().getRegistryKey().getValue().toString().equals(homeDimension);
    }

    public void setHome(BlockPos pos, String dimension) {
        this.homePos = pos.toImmutable();
        this.homeDimension = dimension;
    }

    public SimpleInventory getInventory() { return inventory; }

    // ===================== 交互 =====================

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.getWorld().isClient) return ActionResult.SUCCESS;
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

        if (ownerUuid == null || isOwner(player)) {
            if (ownerUuid == null) setOwner(player); // 无主时第一个右键的人认作主人(兜底,正常走 summon)
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, this.inventory),
                    Text.literal(this.getDisplayName().getString() + " 的背包")));
        } else {
            sayDelayed("我是 " + ownerName() + " 的朋友,你好呀。");
        }
        return ActionResult.CONSUME;
    }

    private String ownerName() {
        PlayerEntity owner = getOwnerPlayer();
        return owner != null ? owner.getName().getString() : "我的主人";
    }

    // ===================== tick(仅服务端) =====================

    @Override
    protected void mobTick() {
        super.mobTick();
        FrendConfig c = FrendConfig.get();

        if (lowHealthWarnCooldown > 0) lowHealthWarnCooldown--;
        if (ambientCooldown > 0) ambientCooldown--;
        if (hurtTalkCooldown > 0) hurtTalkCooldown--;

        // 被动缓慢回血(v0.2 学会吃东西前的保底)
        if (c.passiveRegen && this.age % Math.max(1, c.regenIntervalTicks) == 0
                && this.getHealth() < this.getMaxHealth()) {
            this.heal((float) c.regenAmount);
        }

        // 每秒检查一次主人状态
        if (this.age % 20 == 0) {
            PlayerEntity owner = getOwnerPlayer();
            if (owner != null && owner.isAlive()) {
                // 主人低血提醒
                if (c.ownerLowHealthWarn && owner.getHealth() <= c.lowHealthWarnThreshold
                        && lowHealthWarnCooldown <= 0
                        && this.squaredDistanceTo(owner) < c.chatRadius * c.chatRadius) {
                    lowHealthWarnCooldown = c.lowHealthWarnCooldownSeconds * 20;
                    sayDelayed("你血量见底了!先别冲,吃点东西缓缓。");
                }
                // 偶尔闲聊(主人在身边、白天概率高一点点就免了,保持简单:纯随机 + 长冷却)
                if (c.enableAmbientChat && ambientCooldown <= 0
                        && this.squaredDistanceTo(owner) < 64.0
                        && this.random.nextFloat() < 0.05f) { // 冷却结束后平均再等约 20 秒才开口
                    ambientCooldown = c.ambientChatCooldownSeconds * 20;
                    sayDelayed(AMBIENT_LINES[this.random.nextInt(AMBIENT_LINES.length)]);
                }
            }
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !this.getWorld().isClient && hurtTalkCooldown <= 0 && this.isAlive()) {
            hurtTalkCooldown = 20 * 8;
            sayDelayed(HURT_LINES[this.random.nextInt(HURT_LINES.length)]);
        }
        return hurt;
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!this.getWorld().isClient) {
            say("对不起……我先走一步,东西都留给你。");
        }
        super.onDeath(source);
    }

    /** 死亡时把随身背包全部掉出来。 */
    @Override
    protected void dropInventory() {
        super.dropInventory();
        dropAllItems();
    }

    /** 把背包内容散落在脚下(死亡 / dismiss 共用)。 */
    public void dropAllItems() {
        if (this.getWorld().isClient) return;
        ItemScatterer.spawn(this.getWorld(), this.getBlockPos(), this.inventory);
        for (int i = 0; i < inventory.size(); i++) inventory.setStack(i, ItemStack.EMPTY);
    }

    // ===================== 说话 =====================

    /** 立即向聊天半径内玩家广播一句话,格式 &lt;frend&gt; xxx。 */
    public void say(String msg) {
        if (this.getWorld().isClient) return;
        double r = FrendConfig.get().chatRadius;
        Text text = Text.literal("<" + this.getDisplayName().getString() + "> ").formatted(Formatting.AQUA)
                .append(Text.literal(msg).formatted(Formatting.WHITE));
        for (PlayerEntity p : this.getWorld().getPlayers()) {
            if (p.squaredDistanceTo(this) <= r * r) {
                p.sendMessage(text, false);
            }
        }
    }

    /** 带随机延迟说话(像人:不秒回)。 */
    public void sayDelayed(String msg) {
        FrendConfig c = FrendConfig.get();
        int span = Math.max(1, c.replyDelayMaxTicks - c.replyDelayMinTicks);
        int delay = c.replyDelayMinTicks + this.random.nextInt(span);
        FrendScheduler.schedule(delay, () -> { if (this.isAlive()) say(msg); });
    }

    // ===================== NBT 持久化 =====================

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerUuid != null) nbt.putUuid("FrendOwner", ownerUuid);
        nbt.putString("FrendMode", mode.name());
        if (hasHome()) {
            nbt.putInt("HomeX", homePos.getX());
            nbt.putInt("HomeY", homePos.getY());
            nbt.putInt("HomeZ", homePos.getZ());
            nbt.putString("HomeDim", homeDimension);
        }
        // 背包:拷贝到 DefaultedList 再走原版 Inventories(与 yongye/AccessoryStorage 同款写法)
        DefaultedList<ItemStack> list = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) list.set(i, inventory.getStack(i));
        NbtCompound invTag = new NbtCompound();
        Inventories.writeNbt(invTag, list, this.getWorld().getRegistryManager());
        nbt.put("FrendInventory", invTag);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("FrendOwner")) ownerUuid = nbt.getUuid("FrendOwner");
        try {
            mode = Mode.valueOf(nbt.getString("FrendMode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.FOLLOW;
        }
        if (nbt.contains("HomeDim")) {
            homePos = new BlockPos(nbt.getInt("HomeX"), nbt.getInt("HomeY"), nbt.getInt("HomeZ"));
            homeDimension = nbt.getString("HomeDim");
        }
        if (nbt.contains("FrendInventory")) {
            DefaultedList<ItemStack> list = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
            Inventories.readNbt(nbt.getCompound("FrendInventory"), list, this.getWorld().getRegistryManager());
            for (int i = 0; i < inventory.size(); i++) inventory.setStack(i, list.get(i));
        }
    }
}
