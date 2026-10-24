package com.github.cerealklla.settlemynts.guardhouse;

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
 * dropped for the mob. This is singleton, meaning if multiple guards help the town just gets 1
 * payment. This payment goes to the town hall directly." (explicit user spec, 2026-10-23)
 *
 * <p>Reads the real, already-rolled {@link LivingDropsEvent#getDrops()} collection rather than
 * re-deriving an amount independently -- registered at {@link EventPriority#LOWEST} so it runs
 * after both Yconomics' own gold roll ({@code mob.HostileMobDrops}, default priority) and Lyfe's
 * Gold Find scaling ({@code loot.GoldFindDropListener}, {@link EventPriority#LOW}), so "the same
 * amount which dropped" always matches what a player would actually see on the ground -- no
 * cross-mod re-roll, no risk of the two numbers drifting apart.
 *
 * <p>"Singleton" is satisfied structurally: this handler only ever runs once per kill (one event),
 * and only ever finds *one* qualifying Guard (the first one found among the mob's assist-credited
 * attackers, or the killing blow's own source if Lyfe isn't loaded) rather than iterating every
 * assisting Guard -- so N guards from the same settlement jointly finishing off a mob still produce
 * exactly one deposit. If guards from two different settlements both assisted (a rare, edge-case
 * cross-settlement kill), only the one Guard this resolves to gets paid -- a judgment call, not a
 * design requirement from the spec either way.
 */
public final class GuardKillGoldPayment {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity deadMob = event.getEntity();
        if (!(deadMob.level() instanceof ServerLevel level)) {
            return;
        }
        GuardEntity guard = findAssistingGuard(level, event);
        if (guard == null || guard.getSettlementCoreId() == null) {
            return;
        }
        int goldAmount = sumGoldNuggets(event.getDrops());
        if (goldAmount <= 0) {
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

    private static GuardEntity findAssistingGuard(ServerLevel level, LivingDropsEvent event) {
        if (LyfeAssistBridge.isLoaded()) {
            for (UUID attackerId : LyfeAssistBridge.getAssistedAttackerIds(event.getEntity())) {
                if (level.getEntity(attackerId) instanceof GuardEntity guard) {
                    return guard;
                }
            }
            return null;
        }
        return event.getSource().getEntity() instanceof GuardEntity guard ? guard : null;
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
