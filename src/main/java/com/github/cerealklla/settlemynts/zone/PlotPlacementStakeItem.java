package com.github.cerealklla.settlemynts.zone;

import java.util.UUID;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.rope.RopeFenceLeash;

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
 * "Plot Stakes" (design doc Section 11a, reworked 2026-09-30) -- placed to draw out a new plot's
 * boundary within an already-finalized settlement. Unlike {@code founding.PlannedPerimeterStakeItem},
 * which resolves "which settlement" by proximity search at placement time, this item already carries
 * which settlement granted it (see {@link PlotSessionData#ownerCoreId()}, permanent for the item's
 * lifetime, set once at grant) -- no search needed, and no ambiguity if multiple settlements are
 * nearby.
 *
 * <p><b>{@link PlotSessionData#plotSessionId()} is the item's own mutable "CurrentPlotID," not a
 * permanent binding</b> -- a single granted "Plot Stakes" item is meant to be reused indefinitely
 * across many different plots, not handed out fresh per plot. It starts blank on grant, is filled in
 * lazily (see {@link #useOn}) the first time it actually places a post, is overwritten by {@code
 * GhostPlotStakeEntity#interact} whenever an existing post is right-clicked to resume from, and is
 * reset back to blank the instant the player switches to a different item in hand ({@code
 * SettlemyntsMod#onLeashTick}) -- which is what makes switching away and back always start a
 * genuinely new, disconnected plot instead of silently resuming the old one under a stale PlotID.
 *
 * <p><b>Rope Fence rework (see decisions.md)</b>: placing a post now leashes the placer to it via
 * {@link RopeFenceLeash} (a real, physical rope, not just a marker) -- the new post is linked to the
 * player's current anchor (if any) and becomes the new anchor itself. No placement-time distance
 * check against the anchor -- the physical leash pull (see {@code SettlemyntsMod}'s tick handler)
 * is what keeps placement roughly within {@link RopeFenceLeash#MAX_ROPE_LENGTH_BLOCKS}; a separate
 * hard gate here fought against normal vanilla reach and forced players to face backward to place
 * forward progress (real playtest feedback, 2026-09-29 second pass -- "let the player place the
 * post if they can reach it"). Right-clicking an *existing* open post (rather than bare ground)
 * attaches/resumes there instead -- see {@code GhostPlotStakeEntity#interact}.
 *
 * <p>Not consumed on placement, same reasoning as the Perimeter Stake item -- a Planner should be
 * able to keep placing several plot stakes in a row without walking back for a fresh one each time.
 */
public class PlotPlacementStakeItem extends Item {

    public PlotPlacementStakeItem(Properties properties) {
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
        PlotSessionData session = held.get(ModItems.PLOT_SESSION_DATA);
        if (session == null) {
            player.sendSystemMessage(Component.literal("This stake isn't bound to a settlement -- get a fresh one from the Town Hall Core."));
            return InteractionResult.FAIL;
        }
        if (!(serverLevel.getEntity(session.ownerCoreId()) instanceof GhostTownHallCoreEntity core) || !core.isTownPlanner(player.getUUID())) {
            player.sendSystemMessage(Component.literal("This stake's settlement is gone, or you're no longer a Town Planner there."));
            return InteractionResult.FAIL;
        }

        // Plot Stakes rework (2026-09-30): CurrentPlotID starts blank on every freshly-granted item
        // and is only ever filled in lazily, right here, the first time it actually places a post --
        // not at grant time. Combined with resetting it back to null on every hand-swap
        // (SettlemyntsMod#onLeashTick), this is what makes switching away and back always start a
        // genuinely new, disconnected plot instead of silently resuming the old one under the same
        // PlotID -- the real root cause of an earlier "two disconnected stake chains merged into one
        // plot" bug (see decisions.md).
        UUID plotSessionId = session.plotSessionId();
        if (plotSessionId == null) {
            plotSessionId = UUID.randomUUID();
            held.set(ModItems.PLOT_SESSION_DATA, new PlotSessionData(session.ownerCoreId(), plotSessionId));
        }

        BlockPos placePos = context.getClickedPos().above();
        // Added 2026-09-30, explicit user request: two Town Planners could otherwise stake out
        // overlapping plots (neither sees the other is still in progress) -- this only catches
        // placing *inside an already-finalized* plot; two concurrently in-progress sessions
        // overlapping each other isn't caught until Finalize (see PlotGeometry#overlaps).
        var containingPlot = PlotGeometry.findContainingPlot(serverLevel, core, placePos.getX(), placePos.getZ());
        if (containingPlot.isPresent()) {
            player.sendSystemMessage(Component.literal(
                    "This position is already inside plot \"" + containingPlot.get().name() + "\" -- pick a different spot."));
            return InteractionResult.FAIL;
        }
        // Roadways Milestone 1 (added 2026-10-06, explicit user request): "when placing new Plot
        // Stakes they too cannot intersect with an existing Roadway" -- the mirror-image rule of
        // RoadwayPaver#crossesPlot.
        for (var roadway : com.github.cerealklla.cartographyr.api.Cartography.findEntities(
                serverLevel, com.github.cerealklla.cartographyr.geo.Classification.CONSTRUCTED,
                com.github.cerealklla.cartographyr.geo.Layer.ROADWAY_ID)) {
            if (roadway.geometry().contains(placePos.getX(), placePos.getZ())) {
                player.sendSystemMessage(Component.literal("This position overlaps an existing road -- pick a different spot."));
                return InteractionResult.FAIL;
            }
        }
        GhostPlotStakeEntity anchor = RopeFenceLeash.resolveGhostAnchor(serverLevel, player.getUUID());
        if (anchor != null && !anchor.hasOpenSlot()) {
            player.sendSystemMessage(Component.literal(
                    "Your current Fence Post already has 2 ropes -- right-click a different open post to resume."));
            return InteractionResult.FAIL;
        }

        int placementIndex = GhostPlotStakeEntity.findBySession(serverLevel, session.ownerCoreId(), plotSessionId).size();
        // Directly at placePos, no extra +1 -- user request, 2026-09-29: "I'd like the Plot Perimeter
        // Stakes to be directly placed on the ground now that we are using fences + ropes." The old
        // extra block of height was leftover from when this entity displayed as a floating
        // Blocks.LANTERN marker (see GhostPlotStakeEntity's own doc) and needed to stand visibly above
        // a separate fence-post preview; now that its own display model *is* the Rope Fence Post
        // block, it should sit exactly where a real one would -- also reported as "very difficult to
        // right click," plausibly because a player naturally aims at ground level (where the visual
        // fence post appears to stand), not the unintuitive floating height the hitbox actually sat at.
        GhostPlotStakeEntity newPost = GhostPlotStakeEntity.create(serverLevel, placePos.getX(), placePos.getY(), placePos.getZ(),
                session.ownerCoreId(), plotSessionId, placementIndex);
        if (anchor != null) {
            anchor.addLink(newPost.getUUID());
            newPost.addLink(anchor.getUUID());
            // Real vanilla leash rendering, not painted tripwire -- "I like the visual of the normal
            // rope between the posts." newPost is the leashee, anchor the holder (same direction every
            // time a post is freshly placed; see GhostPlotStakeEntity#interact for the other direction
            // resuming from an open post takes, which doesn't create a link itself).
            newPost.setLeashedTo(anchor, true);
        }
        RopeFenceLeash.setGhostAnchor(serverPlayer, newPost);
        return InteractionResult.SUCCESS_SERVER;
    }
}
