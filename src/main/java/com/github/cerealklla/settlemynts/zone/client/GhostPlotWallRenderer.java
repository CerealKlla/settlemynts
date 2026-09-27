package com.github.cerealklla.settlemynts.zone.client;

import com.github.cerealklla.settlemynts.zone.GhostPlotWallEntity;
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

/**
 * {@link GhostPlotWallEntity}'s client renderer -- same floating-block-item technique as {@code
 * founding.client.GhostBoundaryWallRenderer}, but reads the block to float from the entity's own
 * synced {@code WALL_BLOCK} data instead of a single fixed item, so different plot types render as
 * visibly different colors.
 */
public final class GhostPlotWallRenderer extends EntityRenderer<GhostPlotWallEntity, GhostPlotWallRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostPlotWallRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostPlotWallRenderState createRenderState() {
        return new GhostPlotWallRenderState();
    }

    @Override
    public void extractRenderState(GhostPlotWallEntity entity, GhostPlotWallRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(entity.getWallBlock().getBlock().asItem()), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostPlotWallRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.5, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
