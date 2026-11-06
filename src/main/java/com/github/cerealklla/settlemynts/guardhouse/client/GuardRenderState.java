package com.github.cerealklla.settlemynts.guardhouse.client;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Carries {@link com.github.cerealklla.settlemynts.guardhouse.GuardEntity#isFemale()} across to
 * {@link GuardRenderer#getTextureLocation} -- added 2026-11-05, since the base {@link
 * HumanoidRenderState} snapshot has no such field and a renderer's {@code getTextureLocation} only
 * ever sees the render state, never the live entity.
 */
public final class GuardRenderState extends HumanoidRenderState {
    public boolean female;
}
