package com.frend.client.render;

import com.frend.entity.FrendEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
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
    }

    @Override
    public Identifier getTexture(FrendEntity entity) {
        return TEXTURE;
    }
}
