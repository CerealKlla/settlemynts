package com.github.cerealklla.settlemynts.guardhouse.client;

import com.mojang.blaze3d.vertex.PoseStack;

import com.github.cerealklla.settlemynts.guardhouse.GuardEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteGetter;

/**
 * Renders {@link GuardEntity}'s cloak -- backlog item 12 ("Town Guards get Cloaks once the Town
 * Hall is Tier 3+, tinted the Town-Hall-defined settlement color... once a Guardhouse reaches Tier
 * 3+, its guards' cloaks also show the settlement banner, in a field of the settlement color").
 * Added to {@link GuardRenderer} alongside its existing {@code HumanoidArmorLayer} -- see that
 * class's own doc.
 *
 * <p>Reuses {@link BannerRenderer#submitPatterns} directly, the exact same public static method
 * vanilla's own banner block-entity renderer calls for a real placed banner -- submits the base
 * color layer (tinted by {@link GuardRenderState#cloakColor}) and then every one of {@link
 * GuardRenderState#cloakBannerPatterns}'s layers on top (an empty list if {@link
 * GuardRenderState#showBanner} is false, which renders as a plain solid-color cloak with no pattern
 * at all) -- no new texture-baking code needed, since "a cloak tinted a color, with a banner
 * pattern in a field of that color" is already precisely what a banner's own base+pattern
 * rendering means.
 */
public final class GuardCloakLayer extends RenderLayer<GuardRenderState, HumanoidModel<GuardRenderState>> {

    private final GuardCloakModel model;
    private final SpriteGetter sprites;

    public GuardCloakLayer(RenderLayerParent<GuardRenderState, HumanoidModel<GuardRenderState>> renderer,
                            EntityModelSet modelSet, SpriteGetter sprites) {
        super(renderer);
        this.model = new GuardCloakModel(modelSet.bakeLayer(ModGuardModelLayers.GUARD_CLOAK));
        this.sprites = sprites;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
                        GuardRenderState state, float yRot, float xRot) {
        if (state.isInvisible || !state.showCloak) {
            return;
        }
        poseStack.pushPose();
        // A static cloak (phase 0) reads as a fixed, slight tilt from GuardCloakModel's own
        // constant flutter term -- no per-tick animation state needed for this mob.
        BannerRenderer.submitPatterns(sprites, poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY,
                this.model, 0.0F, true, state.cloakColor, state.cloakBannerPatterns, null);
        poseStack.popPose();
    }
}
