package com.github.cerealklla.settlemynts.raid;

import java.util.Optional;

import com.github.cerealklla.settlemynts.api.Settlemynts;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Real gap, 2026-11-14: vanilla's own Bad Omen -> Raid trigger is gated on {@code
 * ServerLevel#isVillage}, which has no concept of a player-founded Settlemynts settlement at all
 * -- a Mayor's own town never qualifies, only a real (NPC-generated) village does. User request:
 * "these should work in any settlement."
 *
 * <p>Rather than fight vanilla's own village-detection gate, this drives the same underlying raid
 * system directly -- {@code Raids#createOrExtendRaid} is the exact entry point vanilla's own
 * per-tick check already calls once it decides a raid should start/continue, and it does no
 * independent re-validation of "is this actually a village" itself. Natural (NPC-generated)
 * villages are deliberately skipped here (via {@code Settlemynts#isNaturalSettlement}) -- vanilla's
 * own gate already covers those; this only fills the gap for a player-founded settlement.
 *
 * <p>Mirrors vanilla's own observed behavior of consuming Bad Omen once it's actually caused a raid
 * to start/extend, rather than leaving it on the player to re-trigger (or re-extend) indefinitely
 * just by standing still in a settlement -- same one-shot-per-captain-kill economy vanilla itself
 * enforces for a real village.
 */
public final class BadOmenSettlementRaidListener {

    private static final int SCAN_INTERVAL_TICKS = 20; // 1 real second

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.hasEffect(MobEffects.BAD_OMEN)) {
            return;
        }
        if (player.tickCount % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = player.blockPosition();
        Optional<Settlemynts.PlotHandle> plot = Settlemynts.findPlotAt(level, pos);
        if (plot.isEmpty() || Settlemynts.isNaturalSettlement(level, plot.get().settlementCoreId())) {
            return;
        }

        if (level.getRaids().createOrExtendRaid(player, pos) != null) {
            player.removeEffect(MobEffects.BAD_OMEN);
        }
    }
}
