package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
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
 * {@link GhostTownHallCoreEntity}'s client renderer. Same "no dedicated model yet, float a
 * thematically-close vanilla item icon" approach as Yconomics' {@code LootBagRenderer} -- a Bell,
 * since that's vanilla's closest existing "town hall"-flavored item. Also what makes this entity
 * exist client-side at all without crashing (every custom {@code Entity} needs *some} registered
 * renderer, see that class's own doc for the real bug this avoids).
 *
 * <p><b>Not translucent yet</b> -- design doc Section 2 calls for a "ghost" look (partial
 * transparency), but this renders the icon at full opacity for now. The functionally important
 * part of "ghost" -- only certain players can see this entity at all -- is already correct (see
 * {@link GhostTownHallCoreEntity#broadcastToPlayer}); the visual polish is a follow-up, not
 * required for this entity to behave correctly. Flagged explicitly rather than left silent, same
 * as every other placeholder-art decision in this suite.
 */
public final class GhostTownHallCoreRenderer extends EntityRenderer<GhostTownHallCoreEntity, GhostTownHallCoreRenderState> {

    private final ItemModelResolver itemModelResolver;

    public GhostTownHallCoreRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    public GhostTownHallCoreRenderState createRenderState() {
        return new GhostTownHallCoreRenderState();
    }

    @Override
    public void extractRenderState(GhostTownHallCoreEntity entity, GhostTownHallCoreRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        // Built here, not as a static field -- a static-field ItemStack crashed resource-pack
        // loading in this exact codebase before ("Components not bound yet" -- see Yconomics'
        // decisions.md, 2026-09-25). Cheap enough to build fresh each call.
        itemModelResolver.updateForNonLiving(state.icon, new ItemStack(Items.BELL), ItemDisplayContext.GROUND, entity);
    }

    @Override
    public void submit(GhostTownHallCoreRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0, 0.25, 0.0);
        state.icon.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, EntityRenderState.NO_OUTLINE);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }
}
