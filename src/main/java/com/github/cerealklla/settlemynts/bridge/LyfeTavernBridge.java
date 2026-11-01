package com.github.cerealklla.settlemynts.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;

/**
 * Lyfe's Tavern-related mechanisms: the real sit-able {@code com.github.cerealklla.lyfe.rest.StoolBlock}/
 * {@code SeatEntity} furniture (reused here instead of a bespoke placeholder visual, 2026-10-31
 * correction -- see {@code tavern.TavernStoolItem}'s own doc) and the Companion Token mechanism
 * (its own design-document.md, {@code companion} package) -- design doc Section 17's "companion
 * summoning token, same concept to how Allays work." Same isolation convention as every other bridge
 * in this package: no other class may reference {@code com.github.cerealklla.lyfe.*} directly.
 */
public final class LyfeTavernBridge {

    private LyfeTavernBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    /** Places a real Lyfe Stool Block -- the Tavern Stool's actual in-world appearance. */
    public static void placeStool(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, com.github.cerealklla.lyfe.registration.ModBlocks.STOOL.get().defaultBlockState());
    }

    public static void removeStool(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    public static boolean isStool(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof com.github.cerealklla.lyfe.rest.StoolBlock;
    }

    /** Mounts {@code rider} onto the real seat mount point at {@code pos}, same as a player sitting. */
    public static void seatOnStool(ServerLevel level, BlockPos pos, Entity rider) {
        rider.startRiding(com.github.cerealklla.lyfe.rest.SeatEntity.getOrCreate(level, pos));
    }

    /**
     * Mints a Companion Token bound to {@code hiredNpc}'s current live state and gives it to {@code
     * player} -- see {@code api.Lyfe#mintCompanionToken}'s own doc. Does not discard {@code
     * hiredNpc}; {@link com.github.cerealklla.settlemynts.tavern.TavernHireHandler} does that itself
     * right after this call, same as every other "pick this entity up into an item" flow in this mod.
     */
    public static void mintCompanionToken(ServerPlayer player, LivingEntity hiredNpc, String displayName) {
        com.github.cerealklla.lyfe.api.Lyfe.mintCompanionToken(player, hiredNpc, displayName);
    }
}
