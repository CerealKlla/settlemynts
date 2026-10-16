package com.github.cerealklla.settlemynts.guardhouse;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModEntities;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The held form of a picked-up {@link GuardKitStandEntity} -- Guard Kit Stand overhaul, 2026-10-16.
 * Plain {@code Item} (not {@code BlockItem}, mirrors {@code roadway.RoadwayStakeItem}), carries
 * {@link GuardKitStandData}. {@code useOn} re-validates {@code zone.PlotPermissions#canManage} and
 * that the clicked position is still inside the exact plot this stand belongs to (via {@code
 * zone.PlotGeometry#findContainingPlot}, not just "somewhere in the settlement") before spawning a
 * fresh entity and restoring its identity -- {@link GuardKitStandEntity#setPlotIdentity}, {@code
 * Entity#setUUID(standId)} (safe here since the original entity was fully discarded on pickup, see
 * {@code GuardKitStandInteraction#pickUp}), plus the bound Kyt/assigned guard/respawn-timer fields.
 */
public class GuardKitStandItem extends Item {

    public GuardKitStandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }

        ItemStack held = context.getItemInHand();
        GuardKitStandData data = held.get(ModItems.GUARD_KIT_STAND_DATA);
        if (data == null) {
            player.sendSystemMessage(Component.literal("This stand has no saved data -- get a fresh one from the Plot Config Sign."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(data.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("This stand's settlement is gone."));
            return InteractionResult.FAIL;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(data.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().above();
        PlotRecord containing = PlotGeometry.findContainingPlot(serverLevel, core, placePos.getX(), placePos.getZ()).orElse(null);
        if (containing == null || !containing.plotId().equals(data.plotId())) {
            player.sendSystemMessage(Component.literal("A Guard Kit Stand can only be placed inside its own plot."));
            return InteractionResult.FAIL;
        }

        GuardKitStandEntity stand = new GuardKitStandEntity(ModEntities.GUARD_KIT_STAND.get(), serverLevel);
        stand.setUUID(data.standId());
        stand.setPos(placePos.getX() + 0.5, placePos.getY(), placePos.getZ() + 0.5);
        stand.setPlotIdentity(data.settlementCoreId(), data.plotId());
        data.boundKytName().ifPresent(stand::setBoundKytName);
        data.assignedGuardId().ifPresent(stand::setAssignedGuardId);
        stand.setRespawnReadyAtGameTime(data.respawnReadyAtGameTime());
        serverLevel.addFreshEntity(stand);

        held.shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }
}
