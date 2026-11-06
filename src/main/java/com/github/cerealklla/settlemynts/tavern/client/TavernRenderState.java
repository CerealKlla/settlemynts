package com.github.cerealklla.settlemynts.tavern.client;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Carries {@link com.github.cerealklla.settlemynts.tavern.TavernNpcEntity#isFemale()} across to
 * {@link TavernNpcRenderer#getTextureLocation} -- direct mirror of {@code guardhouse.client.
 * GuardRenderState}, added 2026-11-05 for the same reason: the base {@link HumanoidRenderState}
 * snapshot has no such field.
 */
public final class TavernRenderState extends HumanoidRenderState {
    public boolean female;
}
