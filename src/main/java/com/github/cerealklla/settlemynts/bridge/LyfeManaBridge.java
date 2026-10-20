package com.github.cerealklla.settlemynts.bridge;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/**
 * Lyfe's Mana stat (design doc Section 24) -- isolates the one {@code
 * com.github.cerealklla.lyfe.api.Lyfe} reference {@code npc.NpcHealthBar} needs. Same isolation
 * convention as every other bridge in this package (see {@code LyfeMagicianBridge}'s own doc for
 * the full "why a runtime {@code isLoaded()} check alone isn't enough" reasoning) -- fixed here
 * 2026-10-20 after that exact mistake was made directly in {@code NpcHealthBar} itself (a real,
 * not-yet-shipped-live bug: calling {@code com.github.cerealklla.lyfe.api.Lyfe.manaNameplateLine}
 * inline there would throw {@code NoClassDefFoundError} the first time any NPC ticks on a
 * Lyfe-less server, regardless of the {@code ModList.isLoaded} guard around it, since loading
 * {@code NpcHealthBar.class} at all verifies every type its methods reference).
 */
public final class LyfeManaBridge {

    private LyfeManaBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    public static Optional<Component> manaNameplateLine(LivingEntity entity) {
        return com.github.cerealklla.lyfe.api.Lyfe.manaNameplateLine(entity);
    }
}
