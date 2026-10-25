package com.github.cerealklla.settlemynts.bridge;

import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Lets a Guard carry and use a bound Allay in a Bottle / Summoning Stone (explicit user spec,
 * 2026-10-25: "Use it once when spawning, and if the summon dies use it again when they come off
 * cooldown") -- see {@code com.github.cerealklla.lyfe.summoning.GuardCompanionSupport}'s own doc for
 * the full behavior this wraps. Same isolation convention as every other bridge in this package.
 */
public final class LyfeSummoningBridge {

    private LyfeSummoningBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    public static boolean isCompanionItem(ItemStack stack) {
        return com.github.cerealklla.lyfe.api.Lyfe.isCompanionItem(stack);
    }

    /** Call periodically while {@code stack} is held by {@code ownerId} at the given position -- summons/re-summons/tracks cooldown, mutating {@code stack}'s own data component in place. */
    public static void tickCompanion(ServerLevel level, ItemStack stack, UUID ownerId, double x, double y, double z, float yRot) {
        com.github.cerealklla.lyfe.api.Lyfe.tickGuardCompanion(level, stack, ownerId, x, y, z, yRot);
    }
}
