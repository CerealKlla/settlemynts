package com.github.cerealklla.settlemynts.roadway;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Granted/refreshed by a {@link RoadwayConnectionMarkerEntity} on a successful ping (design doc
 * Section 16, 2026-10-24) -- a pure marker status (no attribute modifiers of its own); the real
 * movement-speed bonus is applied/removed by {@link TravelerSpeedListener}, which reads this
 * effect's own amplifier (road tier - 1) each player tick, same "decoupled marker status + a
 * listener that reacts to it" shape Lyfe's own {@code fishing.ShipwreckMobEffect} uses for an
 * identical kind of ping-driven buff.
 */
public final class TravelerMobEffect extends MobEffect {

    public TravelerMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
