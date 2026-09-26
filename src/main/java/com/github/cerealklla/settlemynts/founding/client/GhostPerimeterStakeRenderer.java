package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@link GhostPerimeterStakeEntity}'s client renderer. Floats vanilla's own {@code Items#SOUL_TORCH}
 * -- a real, already-blue-flamed vanilla item, matching the design doc's "ghost image of a torch
 * with blue flame" exactly, no new art needed. Same floating-icon technique as {@code
 * GhostTownHallCoreRenderer}/Yconomics' {@code LootBagRenderer} -- see those classes' docs for why
 * (also what avoids the "no renderer registered" client crash every custom entity needs guarding
 * against).
 */
public final class GhostPerimeterStakeRenderer extends EntityRenderer<GhostPerimeterStakeEntity, GhostPerimeterStakeRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostPerimeterStakeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostPerimeterStakeRenderState createRenderState() {
        return new GhostPerimeterStakeRenderState();
    }

    @Override
    public void extractRenderState(GhostPerimeterStakeEntity entity, GhostPerimeterStakeRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(Items.SOUL_TORCH), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostPerimeterStakeRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.25, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
