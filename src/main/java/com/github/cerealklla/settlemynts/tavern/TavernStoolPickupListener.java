package com.github.cerealklla.settlemynts.tavern;

import java.util.Optional;

import com.github.cerealklla.settlemynts.bridge.LyfeTavernBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Sneak + right-click an indexed Tavern Stool to pick it back up -- a normal (non-sneaking)
 * right-click is left alone entirely, so {@code com.github.cerealklla.lyfe.rest.StoolBlock}'s own
 * vanilla sit behavior still works unchanged (a player can sit at the bar, same as any other
 * stool -- "reuse the stool with some added features," 2026-10-31).
 *
 * <p><b>Picking up an occupied stool is allowed too, 2026-11-01</b> -- real user pushback: "expecting
 * me to actually hire all the npcs just to move the stools is wild." A still-seated
 * {@link TavernNpcEntity} is simply discarded (no Companion Token, no payment -- this is rearranging
 * furniture, not a real hire) rather than requiring the full hire flow just to free the stool up for
 * relocation; see {@link TavernHireHandler#pickUpStool}'s own doc for that half.
 */
public final class TavernStoolPickupListener {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown()
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!LyfeTavernBridge.isStool(level, pos)) {
            return;
        }
        GlobalPos globalPos = GlobalPos.of(level.dimension(), pos);
        Optional<TavernStoolIndex.StoolInfo> info = TavernStoolIndex.get(level.getServer()).get(globalPos);
        if (info.isEmpty()) {
            return; // Not a Tavern Stool -- a plain Lyfe Stool Block placed by someone directly.
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS_SERVER);
        TavernHireHandler.pickUpStool(player, level, globalPos);
    }
}
