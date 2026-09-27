package com.github.cerealklla.settlemynts.founding.client;

import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Shared renderer for every ghost boundary/fence-post marker in this mod ({@code
 * founding.GhostBoundaryWallEntity}, {@code zone.GhostPlotWallEntity}, {@code
 * founding.GhostPerimeterFencePostEntity}, {@code zone.GhostPlotFencePostEntity}) -- all four
 * extend vanilla's {@code Display.BlockDisplay}, so they all render identically via vanilla's own
 * {@link DisplayRenderer.BlockDisplayRenderer}. That class's constructor is {@code protected}
 * (vanilla's own pattern for "subclass this," not directly referenceable via a method reference
 * from unrelated code), so this is a trivial subclass that just exposes it -- no rendering logic
 * of its own.
 *
 * <p>Added 2026-09-27 (see decisions.md) replacing four separate near-identical
 * {@code ItemModelResolver}/{@code ItemDisplayContext.GROUND} renderers, which rendered every
 * marker at dropped-item scale/orientation rather than a real full-size block, and (for fence
 * posts specifically) never picked up a fence's own connected-post model at all since item
 * rendering never touches a block's multipart model.
 */
public final class GhostBlockDisplayRenderer extends DisplayRenderer.BlockDisplayRenderer {

    public GhostBlockDisplayRenderer(EntityRendererProvider.Context context) {
        super(context);
    }
}
