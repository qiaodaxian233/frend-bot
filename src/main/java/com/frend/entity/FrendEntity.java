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
 * <p>v0.2 新增:干活任务(砍树/挖石/挖煤铁/回家存箱子,见 entity/task/)、干活时捡拾掉落物、
 * 工具耐久判断(留口气不用报废)、自动进食(食物饱食度换算回血)。
 *
 * <p>刻意不做的:不打怪(v0.3)、平时不捡物(只在干活时捡,不抢主人东西)、不自然生成(只能 /frend summon)。
 * 模式只存服务端 + NBT,客户端渲染不依赖它,所以不需要 DataTracker。任务对象不落盘,重载后 WORK 退回 STAY。
 */
public class FrendEntity extends PathAwareEntity {

    /** 行为模式。WORK = 正在执行任务(任务本身不落盘,重载后退回 STAY)。 */
    public enum Mode { FOLLOW, STAY, GO_HOME, WORK }

    private Mode mode = Mode.FOLLOW;
    private UUID ownerUuid = null;

    /** 当前任务(仅 WORK 模式;不落盘)。 */
    private com.frend.entity.task.FrendTask currentTask = null;
    private int eatCooldown = 0;

    /** 27 格随身背包。 */
    private final SimpleInventory inventory = new SimpleInventory(27);

    /** 家(可空);维度记 Identifier 字符串,如 minecraft:overworld。 */
    private BlockPos homePos = null;
    private String homeDimension = null;

    // ===== 说话冷却(tick,只在服务端 mobTick 递减) =====
    private int lowHealthWarnCooldown = 0;
    private int ambientCooldown = 20 * 60; // 出生一分钟内不闲聊
    private int hurtTalkCooldown = 0;

    // ===== 聊天记忆(不落盘):LLM 上下文 + "对话延续窗口" + 请求节流 =====
    private final java.util.ArrayDeque<String[]> chatHistory = new java.util.ArrayDeque<>();
    private long lastTalkMillis = 0;          // frend 上次开口时间
    private long lastLlmMillis = 0;           // 上次发起 LLM 请求时间
    private volatile boolean llmBusy = false; // 有请求在飞就不再发

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

    // ===================== 任务(v0.2 干活) =====================

    /** 开始一个任务(自动切 WORK 模式,顶掉旧任务)。 */
    public void startTask(com.frend.entity.task.FrendTask task, String announce) {
        if (currentTask != null) currentTask.onStop();
        currentTask = task;
        setMode(Mode.WORK);
        if (announce != null) sayDelayed(announce);
    }

    /** 收工(announce 可空 = 不说话)。 */
    public void stopTask(String announce) {
        if (currentTask != null) {
            currentTask.onStop();
            currentTask = null;
        }
        if (mode == Mode.WORK) setMode(Mode.STAY);
        if (announce != null) sayDelayed(announce);
    }

    public boolean isWorking() { return currentTask != null; }

    public String currentTaskName() { return currentTask != null ? currentTask.name() : null; }

    /** 找一把还能用的工具(耐久留量走配置);没有返回 EMPTY。 */
    public ItemStack findUsableTool(net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        int reserve = FrendConfig.get().toolReserveDurability;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty() || !s.isIn(tag)) continue;
            if (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= reserve) continue; // 留口气
            return s;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 工具磨一点耐久。不走 ItemStack#damage(1.21 签名多变,风险高),
     * 手动 setDamage;真磨没了从背包移除并喊一声。
     */
    public void damageTool(ItemStack tool) {
        if (tool.isEmpty() || !tool.isDamageable()) return;
        tool.setDamage(tool.getDamage() + 1);
        if (tool.getDamage() >= tool.getMaxDamage()) {
            for (int i = 0; i < inventory.size(); i++) {
                if (inventory.getStack(i) == tool) {
                    inventory.setStack(i, ItemStack.EMPTY);
                    break;
                }
            }
            sayDelayed("啊,工具报废了……回头给我补一把?");
        }
    }

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

    /**
     * 从背包里找一份食物吃掉:回血量 = 食物饱食度(实体没有饥饿系统,用这个当口粮换算)。
     * 【待编译验证】1.21 数据组件:DataComponentTypes.FOOD / FoodComponent#nutrition()。
     */
    private boolean tryEat() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            net.minecraft.component.type.FoodComponent food = s.get(net.minecraft.component.DataComponentTypes.FOOD);
            if (food == null) continue;
            s.decrement(1);
            this.heal(Math.max(1.0f, food.nutrition()));
            this.playSound(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EAT.value(), 1.0f, 1.0f); // 【待编译验证】RegistryEntry 还是 SoundEvent,报错就去 .value()
            if (this.random.nextFloat() < 0.3f) sayDelayed("先垫两口,不耽误事。");
            return true;
        }
        return false;
    }

    // ===================== tick(仅服务端) =====================

    @Override
    protected void mobTick() {
        super.mobTick();
        FrendConfig c = FrendConfig.get();

        if (lowHealthWarnCooldown > 0) lowHealthWarnCooldown--;
        if (ambientCooldown > 0) ambientCooldown--;
        if (hurtTalkCooldown > 0) hurtTalkCooldown--;
        if (eatCooldown > 0) eatCooldown--;

        // ===== 任务驱动(WORK 模式) =====
        if (currentTask != null) {
            if (mode != Mode.WORK) {
                // 被"跟我来"等切走模式 → 任务自然作废
                currentTask.onStop();
                currentTask = null;
            } else if (!currentTask.tick()) {
                currentTask = null;
                setMode(Mode.STAY);
            }
        }

        // 干活时顺手捡脚边掉落物(只在任务中捡,平时不跟主人抢地上的东西)
        if (currentTask != null && this.age % 10 == 0) {
            for (net.minecraft.entity.ItemEntity item : this.getWorld().getEntitiesByClass(
                    net.minecraft.entity.ItemEntity.class, this.getBoundingBox().expand(2.5),
                    e -> e.isAlive() && e.isOnGround())) {
                ItemStack rest = inventory.addStack(item.getStack());
                if (rest.isEmpty()) item.discard();
                else item.setStack(rest);
            }
        }

        // ===== 自动进食:背包里有吃的、血不满就自己吃(优先于被动回血) =====
        if (c.autoEat && eatCooldown <= 0 && this.getHealth() < (float) c.autoEatBelowHealth) {
            if (tryEat()) eatCooldown = c.eatCooldownSeconds * 20;
        }

        // 被动缓慢回血(没吃的时的保底)
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
        this.lastTalkMillis = System.currentTimeMillis(); // 对话延续窗口从这一刻起算
        rememberChat("assistant", msg);
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

    // ===================== 聊天记忆 / 对话窗口 / LLM 节流 =====================

    /** 记一条对话(role: user/assistant)。只留最近 llmHistoryEntries 条,不落盘。 */
    public void rememberChat(String role, String content) {
        chatHistory.addLast(new String[]{role, content});
        int max = Math.max(2, FrendConfig.get().llmHistoryEntries);
        while (chatHistory.size() > max) chatHistory.pollFirst();
    }

    /** 最近对话快照(时间先后顺序)。 */
    public java.util.List<String[]> chatHistorySnapshot() {
        return new java.util.ArrayList<>(chatHistory);
    }

    /** frend 刚说完话的一小段时间内,主人不喊名字也算在跟它聊。 */
    public boolean inConversationWindow() {
        return System.currentTimeMillis() - lastTalkMillis
                < FrendConfig.get().conversationWindowSeconds * 1000L;
    }

    /** 尝试占用一次 LLM 请求名额(有请求在飞或间隔太短则拒绝)。成功后必须调 {@link #finishLlm()}。 */
    public boolean tryStartLlm() {
        long now = System.currentTimeMillis();
        if (llmBusy || now - lastLlmMillis < FrendConfig.get().llmMinIntervalSeconds * 1000L) return false;
        llmBusy = true;
        lastLlmMillis = now;
        return true;
    }

    public void finishLlm() {
        llmBusy = false;
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
        if (mode == Mode.WORK) mode = Mode.STAY; // 任务不落盘,重载后回待命
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
