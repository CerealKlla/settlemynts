package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * {@link GhostTownHallCoreEntity}'s client renderer -- a deliberate no-op. The Core used to float
 * a vanilla {@code Items.BELL} *item icon* itself; as of 2026-10-05 the Core is a pure invisible
 * anchor point, and the real visual is its spawned {@code GhostTownHallBellEntity} child (a real
 * Bell block, see that class's own doc) -- this class still has to exist (every custom {@code
 * Entity} needs *some* registered renderer, see the previous version's own doc for the crash this
 * avoids), it just no longer draws anything.
 */
public final class GhostTownHallCoreRenderer extends EntityRenderer<GhostTownHallCoreEntity, EntityRenderState> {

    public GhostTownHallCoreRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
