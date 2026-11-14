package com.github.cerealklla.settlemynts.raid.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.founding.SettlementCoreIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Real root cause, 2026-11-15: a player's Bad Omen correctly "converted" (vanilla's own {@code
 * BadOmenMobEffect} -> Raid Omen, confirmed against the decompiled source), but the follow-up raid
 * never did anything. Two real vanilla facts, both confirmed against the decompiled source rather
 * than assumed: (1) Bad Omen doesn't trigger a raid directly at all -- it converts to a 30-second
 * Raid Omen effect (gated on {@code level.isVillage(pos)}), and the ACTUAL {@code
 * Raids#createOrExtendRaid} call only fires once Raid Omen's own duration counts down to its last
 * tick, using the position captured back when the conversion happened. (2) {@code Raid#tick()}
 * re-checks {@code level.isVillage(this.center)} on EVERY tick for the raid's entire life, and
 * silently calls {@code stop()} the instant that check fails with zero waves yet spawned --
 * regardless of how the {@code Raid} was created. The original {@code
 * raid.BadOmenSettlementRaidListener} bypass (a direct one-time {@code createOrExtendRaid} call)
 * only ever covered the creation step, not this ongoing re-validation, so a raid inside a
 * player-founded settlement could never survive its own first tick -- it's been removed as
 * superseded by this fix, which is both more complete and lets vanilla's own proper staged
 * Bad-Omen -> Raid-Omen -> Raid flow run entirely unmodified.
 *
 * <p>{@code ServerLevel#isCloseToVillage(BlockPos, int)} is the single real chokepoint: {@code
 * isVillage(BlockPos)}/{@code isVillage(SectionPos)} both delegate straight to it, and every one of
 * {@code Raid}'s own per-tick re-checks calls {@code isVillage} too -- fixing it here fixes the
 * entire pipeline (plus every other vanilla village-adjacent mechanic: iron golem spawning,
 * zombie-siege/patrol avoidance, villager AI) for a player-founded settlement at once, not just
 * raids specifically.
 *
 * <p>Injected at {@code RETURN}, not {@code HEAD}, and gated behind a cheap {@code
 * SettlementCoreIndex#all().isEmpty()} short-circuit first -- vanilla's own POI-density check
 * ({@code PoiManager#sectionsToVillage}) is cheap and already covers the common "real village"
 * case; this mod's own (comparatively expensive, settlement-polygon-scanning) {@code findPlotAt}
 * fallback only ever runs when vanilla's own check already said no, and never at all on a world
 * with zero player-founded settlements. Called from several per-NPC AI goals
 * ({@code MoveThroughVillageGoal}, {@code PathfindToRaidGoal}, etc.) as well as {@code Raid}'s own
 * per-tick loop, so keeping the common-case cost at effectively zero matters here.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelVillageMixin {

    @Inject(
            method = "isCloseToVillage(Lnet/minecraft/core/BlockPos;I)Z",
            at = @At("RETURN"),
            cancellable = true
    )
    private void settlemynts$treatSettlementAsVillage(BlockPos pos, int sectionDistance, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        if (SettlementCoreIndex.get(level.getServer()).all().isEmpty()) {
            return;
        }
        Optional<Settlemynts.PlotHandle> plot = Settlemynts.findPlotAt(level, pos);
        if (plot.isPresent() && !Settlemynts.isNaturalSettlement(level, plot.get().settlementCoreId())) {
            cir.setReturnValue(true);
        }
    }
}
