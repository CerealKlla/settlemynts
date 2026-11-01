package com.github.cerealklla.settlemynts.tavern;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
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
 * The held form of a picked-up {@link TavernStoolEntity} -- direct mirror of {@code
 * guardhouse.GuardKitStandItem}'s own {@code useOn} (re-validates {@code
 * zone.PlotPermissions#canManage} and that the clicked position is still inside the exact plot this
 * stool belongs to).
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

        BlockPos placePos = context.getClickedPos().above();
        PlotRecord containing = PlotGeometry.findContainingPlot(serverLevel, core, placePos.getX(), placePos.getZ()).orElse(null);
        if (containing == null || !containing.plotId().equals(data.plotId())) {
            player.sendSystemMessage(Component.literal("A Tavern Stool can only be placed inside its own plot."));
            return InteractionResult.FAIL;
        }

        TavernStoolEntity stool = new TavernStoolEntity(com.github.cerealklla.settlemynts.registration.ModEntities.TAVERN_STOOL.get(), serverLevel);
        stool.setUUID(data.stoolId());
        float facingYaw = player.getYRot() + 180.0F;
        stool.snapTo(placePos.getX() + 0.5, placePos.getY(), placePos.getZ() + 0.5, facingYaw, 0.0F);
        stool.setPlotIdentity(data.settlementCoreId(), data.plotId());
        data.seatedNpcId().ifPresent(stool::setSeatedNpcId);
        serverLevel.addFreshEntity(stool);

        held.shrink(1);
        return InteractionResult.SUCCESS_SERVER;
    }
}
