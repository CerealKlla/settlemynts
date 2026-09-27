package com.github.cerealklla.settlemynts.zone.client;

import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
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
 * {@link GhostPlotStakeEntity}'s client renderer. Floats vanilla's own {@code Items#LANTERN} --
 * deliberately a different vanilla item than {@code founding.GhostPerimeterStakeEntity}'s soul
 * torch, so a Town Planner can tell a plot stake apart from a settlement perimeter stake at a
 * glance. Same floating-icon technique as every other ghost entity here.
 */
public final class GhostPlotStakeRenderer extends EntityRenderer<GhostPlotStakeEntity, GhostPlotStakeRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostPlotStakeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostPlotStakeRenderState createRenderState() {
        return new GhostPlotStakeRenderState();
    }

    @Override
    public void extractRenderState(GhostPlotStakeEntity entity, GhostPlotStakeRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(Items.LANTERN), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostPlotStakeRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.25, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
