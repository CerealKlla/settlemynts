package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.GhostPerimeterFencePostEntity;
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
 * {@link GhostPerimeterFencePostEntity}'s client renderer -- same floating-block-item technique as
 * {@code GhostBoundaryWallRenderer}, floating vanilla's {@code Items.OAK_FENCE} so the live
 * in-progress preview visually reads as distinct from the finalized wall's wool blocks.
 */
public final class GhostPerimeterFencePostRenderer extends EntityRenderer<GhostPerimeterFencePostEntity, GhostPerimeterFencePostRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostPerimeterFencePostRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostPerimeterFencePostRenderState createRenderState() {
        return new GhostPerimeterFencePostRenderState();
    }

    @Override
    public void extractRenderState(GhostPerimeterFencePostEntity entity, GhostPerimeterFencePostRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(Items.OAK_FENCE), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostPerimeterFencePostRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.5, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
