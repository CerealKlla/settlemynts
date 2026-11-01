package com.github.cerealklla.settlemynts.tavern;

import com.github.cerealklla.settlemynts.bridge.LyfeTavernBridge;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotPermissions;
import com.github.cerealklla.settlemynts.zone.PlotRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
 * The held form of a granted Tavern Stool. Places a real {@code
 * com.github.cerealklla.lyfe.rest.StoolBlock} (2026-10-31 correction -- the original first pass
 * floated a placeholder {@code Blocks.OAK_SLAB} display entity instead of reusing this suite's
 * already-existing, real sit-able stool furniture; see {@link TavernStoolIndex}'s own doc) and
 * records its position via {@link TavernStoolIndex}, keyed by {@link GlobalPos} since the stool is
 * now a plain block, not a picked-up entity. Re-validates {@code zone.PlotPermissions#canManage} and
 * that the clicked position is still inside the exact plot this stool was issued for, same as
 * {@code guardhouse.GuardKitStandItem}.
 */
public class TavernStoolItem extends Item {

    public TavernStoolItem(Properties properties) {
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
        if (!LyfeTavernBridge.isLoaded()) {
            player.sendSystemMessage(Component.literal("Tavern Stools require Lyfe to be installed."));
            return InteractionResult.FAIL;
        }

        ItemStack held = context.getItemInHand();
        TavernStoolData data = held.get(ModItems.TAVERN_STOOL_DATA.get());
        if (data == null) {
            player.sendSystemMessage(Component.literal("This stool has no saved data -- get a fresh one from the Plot Config Sign."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(data.settlementCoreId()) instanceof GhostTownHallCoreEntity core)) {
            player.sendSystemMessage(Component.literal("This stool's settlement is gone."));
            return InteractionResult.FAIL;
        }
        PlotRecord plot = core.getPlots().stream().filter(p -> p.plotId().equals(data.plotId())).findFirst().orElse(null);
        if (plot == null || !PlotPermissions.canManage(plot, core, player.getUUID())) {
            player.sendSystemMessage(Component.literal("You can't manage this plot."));
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().relative(context.getClickedFace());
        if (!serverLevel.getBlockState(placePos).canBeReplaced()) {
            player.sendSystemMessage(Component.literal("Can't place a Tavern Stool there."));
            return InteractionResult.FAIL;
        }
        PlotRecord containing = PlotGeometry.findContainingPlot(serverLevel, core, placePos.getX(), placePos.getZ()).orElse(null);
        if (containing == null || !containing.plotId().equals(data.plotId())) {
            player.sendSystemMessage(Component.literal("A Tavern Stool can only be placed inside its own plot."));
            return InteractionResult.FAIL;
        }

        LyfeTavernBridge.placeStool(serverLevel, placePos);
        TavernStoolIndex.get(serverLevel.getServer()).put(
                GlobalPos.of(serverLevel.dimension(), placePos), data.settlementCoreId(), data.plotId());

        held.shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }
}
