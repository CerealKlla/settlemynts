package com.github.cerealklla.settlemynts.zone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;

/** Render state for {@link com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity} -- see {@link GhostPlotStakeRenderer}. */
public class GhostPlotStakeRenderState extends EntityRenderState {
    public final ItemStackRenderState icon = new ItemStackRenderState();
}
