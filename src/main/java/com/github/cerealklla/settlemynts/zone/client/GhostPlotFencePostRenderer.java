package com.github.cerealklla.settlemynts.zone.client;

import com.github.cerealklla.settlemynts.zone.GhostPlotFencePostEntity;
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
 * {@link GhostPlotFencePostEntity}'s client renderer -- same technique as {@code
 * founding.client.GhostPerimeterFencePostRenderer}, floating vanilla's {@code Items.OAK_FENCE}.
 */
public final class GhostPlotFencePostRenderer extends EntityRenderer<GhostPlotFencePostEntity, GhostPlotFencePostRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostPlotFencePostRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostPlotFencePostRenderState createRenderState() {
        return new GhostPlotFencePostRenderState();
    }

    @Override
    public void extractRenderState(GhostPlotFencePostEntity entity, GhostPlotFencePostRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(Items.OAK_FENCE), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostPlotFencePostRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.5, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
