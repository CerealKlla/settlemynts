package com.github.cerealklla.settlemynts.founding.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;

/** Render state for {@link com.github.cerealklla.settlemynts.founding.GhostBoundaryWallEntity} -- see {@link GhostBoundaryWallRenderer}. */
public class GhostBoundaryWallRenderState extends EntityRenderState {
    public final ItemStackRenderState icon = new ItemStackRenderState();
}
