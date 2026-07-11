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

    // ===== v0.8 弓箭远程 =====
    /** 拉弓蓄力 tick(0 = 没在拉)。 */
    private int bowDrawTicks = 0;
    /** 换武器防抖冷却(滞回:远于 9 格换弓,近于 4 格换近战,20 tick 内不再换)。 */
    private int swapCooldown = 0;
    /** "没箭了"只喊一次,再摸到箭就复位。 */
    private boolean saidNoArrows = false;

    // ===== v0.14 战斗进修 =====
    /** 跳劈状态:已起跳,待下落落刀。 */
    private boolean critPending = false;
    /** 走位方向(偶尔换边,别绕成钟摆)。 */
    private boolean strafeLeft = true;

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

    /**
     * v0.9 自卫反击:frend 自己被打时由 FrendEntity#damage 调用。
     * 和 onOwnerHurt 的区别:<b>任何模式都生效</b>(STAY 站桩、GO_HOME 走路上被疣猪兽拱了也得还手,
     * 不然就是站着挨打到死——v0.3~v0.8 一直存在的地雷,下界尤其致命:疣猪兽/烈焰人/岩浆怪都不在
     * 主动清怪白名单里,以前打 frend 它只会喊疼)。红线照旧:不还手玩家、不还手同类。
     */
    public void onSelfHurt(LivingEntity attacker) {
        FrendConfig c = FrendConfig.get();
        if (!c.combatEnabled || !c.selfDefense) return;
        if (isInRetreating()) return;
        if (attacker == null || !attacker.isAlive()) return;
        if (attacker instanceof PlayerEntity) return;  // 红线:哪怕玩家打它,也绝不还手
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

        // 外部注入优先,且放在模式门槛之前(v0.9):
        // - 支援主人:onOwnerHurt 注入时已限定 FOLLOW 模式;
        // - 自卫反击:onSelfHurt 刻意不限模式——STAY/GO_HOME 被打也要还手。
        if (injectedTarget != null && injectedTarget.isAlive()
                && frend.squaredDistanceTo(injectedTarget) < (c.combatRange * 2.5) * (c.combatRange * 2.5)) {
            target = injectedTarget;
            injectedTarget = null;
            defendingOwner = true;
            return true;
        }

        // 只在跟随模式主动清怪;WORK 模式干活时也保护自己
        FrendEntity.Mode mode = frend.getMode();
        if (mode != FrendEntity.Mode.FOLLOW && mode != FrendEntity.Mode.WORK) return false;

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
        bowDrawTicks = 0;
        critPending = false; // v0.14 跳劈状态清零,打断后不残留
        // 举盾状态解除
        if (frend.isBlocking()) frend.clearActiveItem();
    }

    @Override
    public void tick() {
        FrendConfig c = FrendConfig.get();
        if (attackCooldown > 0) attackCooldown--;
        if (swapCooldown > 0) swapCooldown--;

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
        } else if (c.strafeInCombat && !hasRangedWeapon() && attackCooldown > 0
                && frend.isOnGround() && frend.getRandom().nextFloat() < 0.15f) {
            // v0.14 走位(Wurst 思路):出手间隙侧移一步半,不站桩换刀;偶尔换边,别绕圈绕成钟摆
            if (frend.getRandom().nextFloat() < 0.25f) strafeLeft = !strafeLeft;
            net.minecraft.util.math.Vec3d toT = target.getPos().subtract(frend.getPos()).normalize();
            double sx = -toT.z * (strafeLeft ? 1.6 : -1.6);
            double sz = toT.x * (strafeLeft ? 1.6 : -1.6);
            frend.getNavigation().startMovingTo(frend.getX() + sx, frend.getY(), frend.getZ() + sz, c.followSpeed);
        }

        // ===== v0.8 距离换武器(滞回防抖:远于 9 格且有弓有箭 → 弓;近于 4 格且有近战 → 剑斧) =====
        if (c.rangedEnabled && swapCooldown <= 0) {
            if (dist > 9.0 && !usingBow() && hasStack(s -> s.getItem() == Items.BOW) && !findArrow().isEmpty()) {
                swapMainHand(s -> s.getItem() == Items.BOW);
                frend.sayDelayed("有点远,吃我一箭!");
            } else if (dist < 4.0 && usingBow()
                    && hasStack(s -> s.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                                  || s.isIn(net.minecraft.registry.tag.ItemTags.AXES))) {
                swapMainHand(s -> s.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                               || s.isIn(net.minecraft.registry.tag.ItemTags.AXES));
            }
        }

        // ===== v0.8 远程攻击:手持弓 → 拉弓蓄力 → 满弓放箭 =====
        if (c.rangedEnabled && usingBow()) {
            rangedAttackTick(c, dist);
            return;
        }

        // ===== 攻击 =====
        int attackInterval = computeAttackInterval(c);
        if (dist <= 3.0 && attackCooldown <= 0) { // 近战够得着才打(原式把攻击间隔当距离用,会 12 格隔空打人)
            // v0.14 跳劈暴击(Wurst 思路):落地状态先起跳,下一 tick 下落中出的那一刀才是暴击——玩家同款
            if (c.critHits && frend.isOnGround() && !critPending) {
                frend.getJumpControl().setActive();
                critPending = true;
                return; // 这 tick 只起跳,下 tick 空中出手
            }
            boolean crit = critPending && !frend.isOnGround() && frend.fallDistance > 0.0f;
            critPending = false;
            attackCooldown = attackInterval;
            if (frend.isBlocking()) frend.clearActiveItem(); // 出拳前放盾
            frend.swingHand(Hand.MAIN_HAND, true);
            frend.tryAttack(target);
            if (crit && target.isAlive()) {
                // 暴击补伤 50% + 原版暴击粒子/音效(tryAttack 不走玩家暴击公式,手动补)
                float bonus = (float) (frend.getAttributeValue(
                        net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE) * 0.5);
                target.damage(frend.getDamageSources().mobAttack(frend), Math.max(1.0f, bonus));
                if (frend.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                    sw.spawnParticles(net.minecraft.particle.ParticleTypes.CRIT,
                            target.getX(), target.getBodyY(0.6), target.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
                }
                frend.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
            }
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
            if (frend.getRandom().nextFloat() < 0.08f) {
                frend.sayDelayed(FIGHT_LINES[frend.getRandom().nextInt(FIGHT_LINES.length)]);
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
        FrendConfig c = FrendConfig.get();
        if (!c.threatTargeting) { // 关掉就退回老实的"打最近的"
            hostiles.sort((a, b) -> Double.compare(
                    frend.squaredDistanceTo(a), frend.squaredDistanceTo(b)));
            return hostiles.get(0);
        }
        // v0.14 威胁优先级(Wurst 思路的目标选择,人话版):
        // 点着的苦力怕天字第一号 > 正在打我朋友的 > 打我的 > 残血先送走(斩杀) > 就近
        PlayerEntity owner = frend.getOwnerPlayer();
        HostileEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (HostileEntity h : hostiles) {
            double score = 0;
            if (h instanceof net.minecraft.entity.mob.CreeperEntity cr && cr.getFuseSpeed() > 0) {
                score += 500; // 已点火,秒切目标
            }
            LivingEntity t = h.getTarget();
            if (owner != null && t == owner) score += 300;
            else if (t == frend) score += 200;
            score += (h.getMaxHealth() - h.getHealth()) * 2.0;         // 残血加权
            score -= Math.sqrt(frend.squaredDistanceTo(h)) * 10.0;     // 距离罚分
            if (score > bestScore) { bestScore = score; best = h; }
        }
        return best;
    }

    /**
     * 主动清怪白名单(设计文档 v0.3):僵尸系/骷髅系/苦力怕。别的敌对怪不主动招惹(末影人看一眼就疯)。
     * v0.9 下界修正:僵尸猪灵在代码里是 ZombieEntity 的子类,但它是中立生物——
     * 主动打一只全族暴走,必须豁免。它先动手会走自卫路径(onSelfHurt),该还手还手。
     */
    private boolean isWhitelisted(LivingEntity e) {
        if (e instanceof net.minecraft.entity.mob.ZombifiedPiglinEntity) return false; // 中立,人不犯我我不犯人
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

    // ===================== v0.8 弓箭远程 =====================

    private boolean usingBow() {
        return frend.getMainHandStack().getItem() == Items.BOW;
    }

    /**
     * 远程攻击一 tick:拉弓期间站桩瞄准(像玩家蓄力),拉满 20 tick 放箭,射后 30 tick 冷却。
     * 没箭 → 喊一声换白刃;目标太远(> combatRange)→ 先收弓让移动逻辑贴近。
     */
    private void rangedAttackTick(FrendConfig c, double dist) {
        net.minecraft.item.ItemStack arrowStack = findArrow();
        if (arrowStack.isEmpty()) {
            if (!saidNoArrows) {
                saidNoArrows = true;
                frend.sayDelayed("没箭了,上白刃!");
            }
            bowDrawTicks = 0;
            frend.clearActiveItem();
            swapMainHand(s -> s.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                           || s.isIn(net.minecraft.registry.tag.ItemTags.AXES));
            return;
        }
        saidNoArrows = false;

        if (attackCooldown > 0) return;                 // 两箭之间收弓歇口气
        if (dist > c.combatRange || !frend.canSee(target)) { // 太远/看不见:不拉弓,让移动逻辑先贴近
            bowDrawTicks = 0;
            frend.clearActiveItem();
            return;
        }

        if (bowDrawTicks == 0) {
            if (frend.isBlocking()) frend.clearActiveItem(); // 弓盾不能同时用
            frend.setCurrentHand(Hand.MAIN_HAND);            // 拉弓动作(客户端可见)
        }
        bowDrawTicks++;
        frend.getNavigation().stop(); // 拉弓站桩,像玩家蓄满力再放

        if (bowDrawTicks >= 20) {
            shootArrow(arrowStack);
            bowDrawTicks = 0;
            attackCooldown = 30;
            frend.clearActiveItem();
        }
    }

    /**
     * 放箭:弹道照抄原版骷髅 AbstractSkeletonEntity#shootAt(抛物线补偿 = 水平距离 × 0.2),
     * 固定小散布(不按难度放水)。射一支耗一支。
     */
    private void shootArrow(net.minecraft.item.ItemStack arrowStack) {
        // 【待编译验证】ProjectileUtil.createArrowProjectile 1.21.1 签名——四参(射手, 箭, 伤害系数, 所用武器);
        // 若报错改三参(去掉最后的武器参数,1.20.x 老签名)。
        net.minecraft.entity.projectile.PersistentProjectileEntity arrow =
                net.minecraft.entity.projectile.ProjectileUtil.createArrowProjectile(
                        frend, arrowStack.copyWithCount(1), 1.0f, frend.getMainHandStack());
        double d = target.getX() - frend.getX();
        double e = target.getBodyY(1.0 / 3.0) - arrow.getY();
        double f = target.getZ() - frend.getZ();
        // v0.14 提前量(Wurst BowAimbot 思路,人话版):按飞行时间预判水平位移,封顶 3 格——骷髅不会,神射手会
        if (FrendConfig.get().bowLeadTarget) {
            double horiz = Math.sqrt(d * d + f * f);
            double flightTicks = horiz / 1.6; // setVelocity speed=1.6 ≈ 每 tick 1.6 格
            net.minecraft.util.math.Vec3d v = target.getVelocity();
            d += Math.max(-3.0, Math.min(3.0, v.x * flightTicks));
            f += Math.max(-3.0, Math.min(3.0, v.z * flightTicks));
        }
        double g = Math.sqrt(d * d + f * f);
        arrow.setVelocity(d, e + g * 0.2, f, 1.6f, 6.0f);
        frend.getWorld().spawnEntity(arrow);
        // 已验证:纯 SoundEvent,不带 .value()
        frend.playSound(net.minecraft.sound.SoundEvents.ENTITY_ARROW_SHOOT, 1.0f, 1.0f);
        arrowStack.decrement(1); // 直接减背包里那组箭
        // 已知欠账(先写完后修):箭是异步击杀,现有记忆埋点只认 tryAttack 白刃,射死的怪暂不进战绩。DEVLOG 有记。
    }

    /** 背包里找一组箭(ItemTags.ARROWS,普通/药水/光灵箭都算)。 */
    private net.minecraft.item.ItemStack findArrow() {
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isIn(net.minecraft.registry.tag.ItemTags.ARROWS)) return s;
        }
        return net.minecraft.item.ItemStack.EMPTY;
    }

    private boolean hasStack(java.util.function.Predicate<net.minecraft.item.ItemStack> want) {
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && want.test(s)) return true;
        }
        return false;
    }

    /** 主手和背包里第一件满足条件的东西对调(原主手物进那个包位,不丢东西)。 */
    private void swapMainHand(java.util.function.Predicate<net.minecraft.item.ItemStack> want) {
        var inv = frend.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !want.test(s)) continue;
            net.minecraft.item.ItemStack old = frend.getMainHandStack();
            frend.setStackInHand(Hand.MAIN_HAND, s.copy());
            inv.setStack(i, old.isEmpty() ? net.minecraft.item.ItemStack.EMPTY : old.copy());
            swapCooldown = 20;
            return;
        }
    }

    // ===== 战斗喊话 =====
    private static final String[] FIGHT_LINES = {
            "拿命来!",
            "别想伤我朋友!",
            "就这?",
            "滚开!",
            "你找死!",
            "我顶上!"
    };
}
