package com.github.cerealklla.settlemynts.guardhouse.client;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Carries {@link com.github.cerealklla.settlemynts.guardhouse.GuardEntity#isFemale()} across to
 * {@link GuardRenderer#getTextureLocation} -- added 2026-11-05, since the base {@link
 * HumanoidRenderState} snapshot has no such field and a renderer's {@code getTextureLocation} only
 * ever sees the render state, never the live entity.
 *
 * <p>{@link #showCloak}/{@link #cloakColor}/{@link #showBanner}/{@link #cloakBannerPatterns} added
 * 2026-11-13 for {@link GuardCloakLayer} (backlog item 12) -- {@link #cloakBannerPatterns} is
 * populated from {@link ClientGuardCloakData}, not read off the entity directly, since {@code
 * BannerPatternLayers} has no {@code EntityDataSerializer} and so never reaches a real {@code
 * SynchedEntityData} snapshot at all (see {@code GuardCloakBannerPayload}'s own doc).
 */
public final class GuardRenderState extends HumanoidRenderState {
    public boolean female;
    public boolean showCloak;
    public DyeColor cloakColor = DyeColor.WHITE;
    public boolean showBanner;
    public BannerPatternLayers cloakBannerPatterns = BannerPatternLayers.EMPTY;
}
