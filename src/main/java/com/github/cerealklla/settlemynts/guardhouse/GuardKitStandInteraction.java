package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import com.github.cerealklla.settlemynts.bridge.KytGuardhouseBridge;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side logic for a Guard Kit Stand's Kit Menu -- opening it (right-click, {@link
 * GuardKitStandEntity#interact}) and "Pick Up" (the only action here that actually removes the
 * entity; every other button -- Create/Edit/Select Kyt -- is handled directly by {@code
 * SettlemyntsMod}'s own payload handlers against the still-placed entity).
 */
public final class GuardKitStandInteraction {

    private GuardKitStandInteraction() {
    }

    public static void open(ServerPlayer player, GuardKitStandEntity stand) {
        boolean guardAlive = stand.assignedGuardId()
                .map(id -> stand.level() instanceof ServerLevel serverLevel && serverLevel.getEntity(id) instanceof LivingEntity guard && guard.isAlive())
                .orElse(false);
        long secondsUntilRespawn = guardAlive || !(stand.level() instanceof ServerLevel serverLevel)
                ? 0
                : Math.max(0, (stand.respawnReadyAtGameTime() - serverLevel.getGameTime()) / 20);
        PacketDistributor.sendToPlayer(player, new OpenGuardKitStandMenuPayload(
                stand.getUUID(), stand.boundKytName(), guardAlive, secondsUntilRespawn, KytGuardhouseBridge.isLoaded(), stand.sparesOnHand()));
    }

    /**
     * "Pick Up" -- discards the entity, inserts (or drops as an overflow fallback) a held item
     * carrying a {@link GuardKitStandData} snapshot of everything needed to resume this exact stand
     * later. Does **not** touch {@code zone.PlotRecord#guardKitStandsIssued} -- picking up and later
     * re-placing is not "destroying" a stand, see that field's own doc. Removes the stand from {@link
     * GuardKitStandIndex} and recomputes the plot's derived Planned Inventory immediately -- {@code
     * Entity#discard()} only flags the entity for removal, {@link GuardKitStandEntity#onRemovedFromLevel}
     * doesn't fire synchronously with it, so both are done explicitly here rather than relied on.
     */
    public static void pickUp(ServerPlayer player, GuardKitStandEntity stand) {
        GuardKitStandData data = new GuardKitStandData(stand.getUUID(), stand.settlementCoreId(), stand.plotId(),
                stand.boundKytName(), stand.assignedGuardId(), stand.respawnReadyAtGameTime(), stand.sparesOnHand());
        ItemStack item = new ItemStack(ModItems.GUARD_KIT_STAND.get());
        item.set(ModItems.GUARD_KIT_STAND_DATA.get(), data);
        UUID settlementCoreId = stand.settlementCoreId();
        UUID plotId = stand.plotId();
        ServerLevel level = stand.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        stand.discard();
        if (level != null) {
            GuardKitStandIndex.get(level.getServer()).remove(stand.getUUID());
            GuardKitStandPreferredInventory.recalculate(level, settlementCoreId, plotId);
        }
        if (!player.getInventory().add(item)) {
            player.drop(item, false);
        }
    }
}
