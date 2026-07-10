package com.frend.entity;

import com.frend.FrendConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.EnumSet;
import java.util.List;

/**
 * v0.3 战斗 Goal:攻击敌对生物 / 支援主人。
 *
 * <p>优先级低于 SwimGoal 和 FrendFollowOwnerGoal,但高于 LookAt。
 * 设计原则(照设计文档 v0.3):
 * <ol>
 *   <li><b>主动清怪</b>:frend 周围 {@code combatRange} 格内有 HostileEntity → 攻击最近的;</li>
 *   <li><b>支援主人</b>:主人受到 LivingEntity 攻击 → 攻击攻击者(主人通知 frend,见 onOwnerHurt);</li>
 *   <li><b>盾牌格挡</b>:副手有盾 → 受击前 2 tick 举盾;</li>
 *   <li><b>低血撤退</b>:血量低于阈值 → 停止攻击、绕主人后退、发出求救。</li>
 * </ol>
 *
 * 刻意不做:主动追杀超远距离怪;攻击玩家;复杂走位——v0.3 够用,后续里程碑再精化。
 */
public class FrendCombatGoal extends Goal {

    private final FrendEntity frend;
    private LivingEntity target;

    /** 从外部注入攻击目标(支援主人时调用)。 */
    private LivingEntity injectedTarget;

    /** 连击冷却:距上次攻击的 tick 数。 */
    private int attackCooldown = 0;

    /** 撤退中计时(tick)。 */
    private int retreatTicks = 0;
    private boolean retreatNoticeSent = false;

    /** 格挡举盾倒计时。 */
    private int blockTicks = 0;

    /** 攻击目标丢失/死亡的累计 tick(超时放弃)。 */
    private int lostTargetTicks = 0;

    /** v0.4:当前目标是否来自"支援主人"注入——干掉它算一次救主。 */
    private boolean defendingOwner = false;

    public FrendCombatGoal(FrendEntity frend) {
        this.frend = frend;
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    // ===================== 外部接口 =====================

    /**
     * 主人被 attacker 攻击时 FrendEntity 调用此方法注入目标(支援主人)。
     * 只有 FOLLOW 模式才支援;WORK/STAY/GO_HOME 不管。
     */
    public void onOwnerHurt(LivingEntity attacker) {
        FrendConfig c = FrendConfig.get();
        if (!c.combatEnabled) return;
        if (frend.getMode() != FrendEntity.Mode.FOLLOW) return;
        if (isInRetreating()) return;
        if (attacker == null || !attacker.isAlive()) return;
        if (attacker instanceof PlayerEntity) return;  // 红线:不打玩家,不做 PVP 工具
        if (attacker instanceof FrendEntity) return;   // 红线:不打同类
        injectedTarget = attacker;
    }

    public boolean isInRetreating() { return retreatTicks > 0; }

    /** 撤退计时递减(Goal 不激活时不会 tick,由 FrendEntity#mobTick 每 tick 调,否则撤退一次就永久和平)。 */
    public void tickRetreatCooldown() {
        if (retreatTicks > 0) {
            retreatTicks--;
            if (retreatTicks == 0) retreatNoticeSent = false; // 下次撤退还能喊
        }
    }
    public LivingEntity getCurrentTarget() { return target; }

    // ===================== Goal 生命周期 =====================

    @Override
    public boolean canStart() {
        FrendConfig c = FrendConfig.get();
        if (!c.combatEnabled) return false;
        // 撤退中不打
        if (isInRetreating()) return false;
        // 只在跟随模式主动清怪;WORK 模式干活时也保护自己
        FrendEntity.Mode mode = frend.getMode();
        if (mode != FrendEntity.Mode.FOLLOW && mode != FrendEntity.Mode.WORK) return false;

        // 外部注入(支援主人)优先
        if (injectedTarget != null && injectedTarget.isAlive()
                && frend.squaredDistanceTo(injectedTarget) < (c.combatRange * 2.5) * (c.combatRange * 2.5)) {
            target = injectedTarget;
            injectedTarget = null;
            defendingOwner = true;
            return true;
        }

        // 自动扫描附近 HostileEntity
        target = findNearestHostile(c.combatRange);
        defendingOwner = false;
        return target != null;
    }

    @Override
    public boolean shouldContinue() {
        FrendConfig c = FrendConfig.get();
        if (!c.combatEnabled) return false;
        if (isInRetreating()) return false;
        if (target == null || !target.isAlive()) return false;
        // 太远了放弃
        return frend.squaredDistanceTo(target) <= (c.combatRange * 3.0) * (c.combatRange * 3.0);
    }

    @Override
    public void stop() {
        target = null;
        lostTargetTicks = 0;
        attackCooldown = 0;
        blockTicks = 0;
        defendingOwner = false;
        // 举盾状态解除
        if (frend.isBlocking()) frend.clearActiveItem();
    }

    @Override
    public void tick() {
        FrendConfig c = FrendConfig.get();
        if (attackCooldown > 0) attackCooldown--;

        // ===== 低血撤退 =====
        if (frend.getHealth() <= (float) c.retreatBelowHealth) {
            if (!retreatNoticeSent) {
                retreatNoticeSent = true;
                frend.sayDelayed("我不行了,先撤!");
            }
            retreatTicks = c.retreatDurationSeconds * 20;
            stop();
            doRetreat();
            return;
        }

        if (target == null || !target.isAlive()) {
            lostTargetTicks++;
            if (lostTargetTicks >= 20) stop(); // 1 秒没找到 → 放弃
            return;
        }
        lostTargetTicks = 0;

        // 看向目标
        frend.getLookControl().lookAt(target, 30.0f, 30.0f);

        double dist = frend.distanceTo(target);

        // ===== 苦力怕点火 → 别贴脸,反向拉开(不然清苦力怕 = 自杀 + 炸地形) =====
        // 【待编译验证】CreeperEntity#getFuseSpeed(>0 = 正在点火)
        if (target instanceof net.minecraft.entity.mob.CreeperEntity creeper && creeper.getFuseSpeed() > 0 && dist < 6.0) {
            double ax = frend.getX() - creeper.getX(), az = frend.getZ() - creeper.getZ();
            double al = Math.sqrt(ax * ax + az * az);
            if (al < 0.01) { ax = 1; al = 1; }
            frend.getNavigation().startMovingTo(
                    frend.getX() + ax / al * 6, frend.getY(), frend.getZ() + az / al * 6,
                    c.followSpeed * 1.2);
            return; // 这 tick 不追不打
        }

        // ===== 盾牌格挡:副手有盾 + 目标近距离时举盾 =====
        if (c.shieldEnabled) {
            net.minecraft.item.ItemStack offhand = frend.getStackInHand(Hand.OFF_HAND);
            boolean hasShield = offhand.getItem() == Items.SHIELD;
            if (hasShield && dist < 5.0 && !frend.isBlocking()) {
                frend.setCurrentHand(Hand.OFF_HAND); // 举盾
                blockTicks = 40;
            }
            if (blockTicks > 0) {
                blockTicks--;
                if (blockTicks <= 0 && frend.isBlocking()) frend.clearActiveItem();
            }
        }

        // ===== 移动:保持 2 格攻击距离,太近退后一点 =====
        double targetDist = hasRangedWeapon() ? 6.0 : 2.0;
        if (dist > targetDist + 1.0) {
            frend.getNavigation().startMovingTo(target, c.followSpeed * 1.1);
        } else if (dist < 1.5 && !hasRangedWeapon()) {
            // 太贴着了,退一步
            frend.getNavigation().stop();
        }

        // ===== 攻击 =====
        int attackInterval = computeAttackInterval(c);
        if (dist <= 3.0 && attackCooldown <= 0) { // 近战够得着才打(原式把攻击间隔当距离用,会 12 格隔空打人)
            attackCooldown = attackInterval;
            if (frend.isBlocking()) frend.clearActiveItem(); // 出拳前放盾
            frend.swingHand(Hand.MAIN_HAND, true);
            frend.tryAttack(target);
            // ===== v0.4 记忆埋点:这一下打死了 → 记击杀;若是救主之战 → 记救主 =====
            if (!target.isAlive()) {
                long now = frend.getWorld().getTime();
                String mobName = target.getName().getString();
                String milestone = frend.getMemory().recordKill(mobName, now);
                if (defendingOwner) {
                    String rescueLine = frend.getMemory().recordRescue(mobName, now);
                    if (rescueLine != null) frend.sayDelayed(rescueLine);
                    defendingOwner = false;
                } else if (milestone != null) {
                    frend.sayDelayed(milestone); // 里程碑一生一次,优先级低于救主感慨
                }
                return; // 目标已死,下 tick 走丢失目标逻辑收尾
            }
            // 偶尔喊话
            if (frend.random.nextFloat() < 0.08f) {
                frend.sayDelayed(FIGHT_LINES[frend.random.nextInt(FIGHT_LINES.length)]);
            }
        }
    }

    // ===================== 私有工具 =====================

    private LivingEntity findNearestHostile(double range) {
        List<HostileEntity> hostiles = frend.getWorld().getEntitiesByClass(
                HostileEntity.class,
                new Box(frend.getBlockPos()).expand(range),
                e -> e.isAlive() && !e.isSpectator() && isWhitelisted(e) && frend.canSee(e));
        if (hostiles.isEmpty()) return null;
        hostiles.sort((a, b) -> Double.compare(
                frend.squaredDistanceTo(a), frend.squaredDistanceTo(b)));
        return hostiles.get(0);
    }

    /** 主动清怪白名单(设计文档 v0.3):僵尸系/骷髅系/苦力怕。别的敌对怪不主动招惹(末影人看一眼就疯)。 */
    private boolean isWhitelisted(LivingEntity e) {
        return e instanceof net.minecraft.entity.mob.ZombieEntity
                || e instanceof net.minecraft.entity.mob.AbstractSkeletonEntity
                || e instanceof net.minecraft.entity.mob.CreeperEntity;
    }

    /** 走向主人背后撤退一段时间。 */
    private void doRetreat() {
        PlayerEntity owner = frend.getOwnerPlayer();
        if (owner == null) return;
        // 在主人身后生成一个逃跑目标点
        double dx = frend.getX() - (target != null ? target.getX() : frend.getX());
        double dz = frend.getZ() - (target != null ? target.getZ() : frend.getZ());
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0) { dx /= len; dz /= len; }
        double rx = owner.getX() + dx * 5;
        double rz = owner.getZ() + dz * 5;
        frend.getNavigation().startMovingTo(rx, owner.getY(), rz, FrendConfig.get().followSpeed * 1.2);
    }

    private boolean hasRangedWeapon() {
        net.minecraft.item.ItemStack main = frend.getMainHandStack();
        return main.getItem() == Items.BOW || main.getItem() == Items.CROSSBOW;
    }

    /**
     * 攻击间隔(tick):有剑/斧快一点(16 tick≈0.8s),其他 20 tick(1s)。
     * 1.21 攻速属性还在,但 frend 是 PathAwareEntity 不是玩家,不走攻速槽;用固定值最安全。
     */
    private int computeAttackInterval(FrendConfig c) {
        net.minecraft.item.ItemStack main = frend.getMainHandStack();
        if (main.getItem() instanceof SwordItem || main.getItem() instanceof AxeItem) return 16;
        return 20;
    }

    // ===== 战斗喊话 =====
    private static final String[] FIGHT_LINES = {
            "拿命来!",
            "别想伤我的主人!",
            "就这?",
            "滚开!",
            "你找死!",
            "我顶上!"
    };
}
