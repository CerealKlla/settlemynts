package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.GhostBoundaryWallEntity;
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
 * {@link GhostBoundaryWallEntity}'s client renderer. Floats vanilla's {@code Items.WHITE_WOOL} --
 * a block-item, so the item-in-world machinery renders it as an actual 3D cube (the same effect a
 * dropped cobblestone shows in vanilla), not a flat sprite -- giving a real "wall of blocks" look
 * strung along the perimeter from many of these at once. Switched from glass to wool 2026-09-26
 * per user request (less transparent, reads as more solid). Same floating-icon technique as every
 * other ghost entity here; see {@code GhostTownHallCoreRenderer}'s doc for why this pattern is
 * used (also what avoids the "no renderer registered" client crash every custom entity needs).
 */
public final class GhostBoundaryWallRenderer extends EntityRenderer<GhostBoundaryWallEntity, GhostBoundaryWallRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostBoundaryWallRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostBoundaryWallRenderState createRenderState() {
        return new GhostBoundaryWallRenderState();
    }

    @Override
    public void extractRenderState(GhostBoundaryWallEntity entity, GhostBoundaryWallRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(Items.WHITE_WOOL), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostBoundaryWallRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.5, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
