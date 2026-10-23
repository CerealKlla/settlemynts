package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.roadway.TravelerMobEffect;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Settlemynts' first custom status effect (design doc Section 16, 2026-10-24) -- see {@code roadway.TravelerMobEffect}'s own doc. */
public final class ModMobEffects {

    private ModMobEffects() {
    }

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, SettlemyntsMod.MODID);

    public static final DeferredHolder<MobEffect, TravelerMobEffect> TRAVELER = MOB_EFFECTS.register(
            "traveler",
            () -> new TravelerMobEffect(MobEffectCategory.BENEFICIAL, 0xC2A14D));
}
