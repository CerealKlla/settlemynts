package com.github.cerealklla.settlemynts.rope;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The real, craftable Rope Fence Post item -- right-clicking bare ground places a new {@link
 * RopeFencePostBlock} and leashes the placer to it (see {@code RopeFenceLeash}); right-clicking an
 * *existing* post that still has an open link slot attaches/resumes the leash there instead of
 * placing a new block ("go back to a Fence Post which does not have 2 ropes on it," per the user's
 * own spec).
 *
 * <p><b>No placement-time distance check against the current anchor</b> (removed in the Rope Fence
 * rework's second pass, see decisions.md) -- a hard "must be within 5 of the anchor" gate fought
 * against normal vanilla reach: once the physical leash pull already keeps the player hugging the
 * 5-block boundary, any forward-facing placement attempt landed *further* than 5 from the anchor,
 * forcing players to turn around and place behind themselves just to stay legal. The physical pull
 * (see {@code SettlemyntsMod}'s tick handler) is the real distance constraint now; placement itself
 * is gated only by normal reach and the "already has 2 ropes" check.
 *
 * <p>The rope itself is real vanilla {@code Leashable} rendering via {@link RopeAnchorEntity}, not
 * painted tripwire -- reusing the same "right-click a fence to attach a rope" spirit vanilla already
 * has for leads, per the user's own suggestion.
 *
 * <p>Client-side placement prediction is left to vanilla ({@code super.useOn}) -- only the server
 * branch does the extra link-tracking/leash bookkeeping, so real block placement still feels
 * instant on the client like any other block item.
 */
public class RopeFencePostItem extends BlockItem {

    public RopeFencePostItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return super.useOn(context);
        }
        Player player = context.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return super.useOn(context);
        }

        BlockPos clickedPos = context.getClickedPos();
        if (serverLevel.getBlockEntity(clickedPos) instanceof RopeFencePostBlockEntity clickedPost) {
            return attachOrResume(serverLevel, serverPlayer, clickedPos, clickedPost);
        }

        BlockPos anchorPos = RopeFenceLeash.resolveRealAnchor(player.getUUID());
        if (anchorPos != null && serverLevel.getBlockEntity(anchorPos) instanceof RopeFencePostBlockEntity anchorEntity && !anchorEntity.hasOpenSlot()) {
            player.sendSystemMessage(Component.literal(
                    "Your current Fence Post already has 2 ropes -- right-click a different open post to resume."));
            return InteractionResult.FAIL;
        }

        BlockPos placePos = clickedPos.relative(context.getClickedFace());
        InteractionResult result = super.useOn(context);
        if (result.consumesAction() && serverLevel.getBlockEntity(placePos) instanceof RopeFencePostBlockEntity newPost) {
            if (anchorPos != null && serverLevel.getBlockEntity(anchorPos) instanceof RopeFencePostBlockEntity anchorEntity) {
                anchorEntity.addLink(placePos);
                newPost.addLink(anchorPos);
                RopeAnchorEntity newAnchor = RopeAnchorEntity.getOrCreate(serverLevel, placePos);
                RopeAnchorEntity oldAnchor = RopeAnchorEntity.getOrCreate(serverLevel, anchorPos);
                newAnchor.setLeashedTo(oldAnchor, true);
            }
            RopeFenceLeash.setRealAnchor(player.getUUID(), placePos);
        }
        return result;
    }

    private InteractionResult attachOrResume(ServerLevel level, ServerPlayer player, BlockPos pos, RopeFencePostBlockEntity postEntity) {
        if (!postEntity.hasOpenSlot()) {
            player.sendSystemMessage(Component.literal("This Fence Post already has 2 ropes attached."));
            return InteractionResult.SUCCESS;
        }
        BlockPos anchorPos = RopeFenceLeash.resolveRealAnchor(player.getUUID());
        if (anchorPos != null && !anchorPos.equals(pos)
                && level.getBlockEntity(anchorPos) instanceof RopeFencePostBlockEntity anchorEntity && anchorEntity.hasOpenSlot()) {
            anchorEntity.addLink(pos);
            postEntity.addLink(anchorPos);
            RopeAnchorEntity newAnchor = RopeAnchorEntity.getOrCreate(level, pos);
            RopeAnchorEntity oldAnchor = RopeAnchorEntity.getOrCreate(level, anchorPos);
            newAnchor.setLeashedTo(oldAnchor, true);
        }
        RopeFenceLeash.setRealAnchor(player.getUUID(), pos);
        player.sendSystemMessage(Component.literal("Rope attached -- continue placing Fence Posts to extend the line."));
        return InteractionResult.SUCCESS;
    }
}
