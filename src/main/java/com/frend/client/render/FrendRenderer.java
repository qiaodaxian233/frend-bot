package com.frend.client.render;

import com.frend.entity.FrendEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/**
 * frend 渲染器:直接用原版玩家模型(宽臂)+ 原版 Steve 皮肤,零自绘贴图。
 *
 * <p>【待编译验证】本仓库首个玩家模型渲染器,yongye 没有先例可抄,风险点:
 * <ul>
 *   <li>PlayerEntityModel 构造签名 (ModelPart, boolean thinArms) —— 1.21.1 yarn</li>
 *   <li>EntityModelLayers.PLAYER 取模型部件</li>
 *   <li>Steve 贴图路径:1.20.2 起搬到 textures/entity/player/wide/steve.png</li>
 * </ul>
 * 若编译报错优先核对这三处。后期换自定义皮肤:把 png 放 assets/frend/textures/entity/frend.png
 * 并改 TEXTURE 即可(皮肤图必须是标准 64×64 玩家皮肤布局)。
 */
@Environment(EnvType.CLIENT)
public class FrendRenderer extends BipedEntityRenderer<FrendEntity, PlayerEntityModel<FrendEntity>> {

    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    public FrendRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        // ===== v0.7 盔甲渲染:BipedEntityRenderer 只带头部/手持物/鞘翅 feature,盔甲要自己挂 =====
        // 照抄原版 PlayerEntityRenderer 的挂法。【待编译验证】ArmorFeatureRenderer 四参构造
        // (context, innerModel, outerModel, BakedModelManager——1.20.2+ 盔甲纹饰需要)与
        // ArmorEntityModel + PLAYER_INNER/OUTER_ARMOR 模型层。手持物由父类自带,若不显示再显式挂
        // HeldItemFeatureRenderer。
        this.addFeature(new ArmorFeatureRenderer<>(this,
                new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public Identifier getTexture(FrendEntity entity) {
        return TEXTURE;
    }
}
