package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.cerealklla.settlemynts.api.Settlemynts;
import com.github.cerealklla.settlemynts.bridge.BlueprintsConstructionBridge;
import com.github.cerealklla.settlemynts.bridge.LyfeAssistBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.ContainerDeposit;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * "Any time a Town Guard kills a mob the town should get paid the same amount of gold which
 * dropped for the mob... This payment goes to the town hall directly." (explicit user spec,
 * 2026-10-23). The original spec paid only one Guard's settlement per kill regardless of how many
 * Guards contributed ("singleton") -- removed 2026-10-24 per explicit follow-up: "let's remove the
 * restriction on guard kill bounties; now all guards that contribute to a kill will be rewarded."
 * Every Guard among the mob's assist-credited attackers now triggers its own independent payment to
 * its own settlement's town hall -- two Guards from the same town jointly finishing off a mob now
 * pays that one town twice, not once; Guards from two different settlements each pay their own town.
 *
 * <p>Reads the real, already-rolled {@link LivingDropsEvent#getDrops()} collection rather than
 * re-deriving an amount independently -- registered at {@link EventPriority#LOWEST} so it runs
 * after both Yconomics' own gold roll ({@code mob.HostileMobDrops}, default priority) and Lyfe's
 * Gold Find scaling ({@code loot.GoldFindDropListener}, {@link EventPriority#LOW}), so "the same
 * amount which dropped" always matches what a player would actually see on the ground -- no
 * cross-mod re-roll, no risk of the two numbers drifting apart. Each paid-out Guard gets the full
 * {@code goldAmount}, not a divided share -- the spec says "the same amount... for the mob" per
 * Guard, not a pool split between contributors.
 */
public final class GuardKillGoldPayment {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity deadMob = event.getEntity();
        if (!(deadMob.level() instanceof ServerLevel level)) {
            return;
        }
        int goldAmount = sumGoldNuggets(event.getDrops());
        if (goldAmount <= 0) {
            return;
        }
        for (GuardEntity guard : findAssistingGuards(level, event)) {
            payGuardsSettlement(level, guard, goldAmount);
        }
    }

    private static void payGuardsSettlement(ServerLevel level, GuardEntity guard, int goldAmount) {
        if (guard.getSettlementCoreId() == null) {
            return;
        }
        if (!(level.getEntity(guard.getSettlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            return;
        }
        PlotRecord townHall = core.townHallPlot().orElse(null);
        if (townHall == null) {
            return;
        }
        List<Container> townHallBoxes = Settlemynts.resolvePlotBoxes(level, townHall.plotId());
        ContainerDeposit.depositIntoAny(townHallBoxes, new net.minecraft.world.item.ItemStack(Items.GOLD_NUGGET, goldAmount));
        if (ModList.get().isLoaded("blueprynts")) {
            BlueprintsConstructionBridge.appendLedgerEntry(level, townHall, "Guard Kill Bounty +" + goldAmount);
        }
    }

    private static List<GuardEntity> findAssistingGuards(ServerLevel level, LivingDropsEvent event) {
        if (LyfeAssistBridge.isLoaded()) {
            List<GuardEntity> guards = new ArrayList<>();
            for (UUID attackerId : LyfeAssistBridge.getAssistedAttackerIds(event.getEntity())) {
                if (level.getEntity(attackerId) instanceof GuardEntity guard) {
                    guards.add(guard);
                }
            }
            return guards;
        }
        return event.getSource().getEntity() instanceof GuardEntity guard ? List.of(guard) : List.of();
    }

    private static int sumGoldNuggets(Iterable<ItemEntity> drops) {
        int total = 0;
        for (ItemEntity itemEntity : drops) {
            if (itemEntity.getItem().is(Items.GOLD_NUGGET)) {
                total += itemEntity.getItem().getCount();
            }
        }
        return total;
    }
}
