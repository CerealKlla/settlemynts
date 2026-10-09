package com.github.cerealklla.settlemynts.founding.client;

import java.util.Optional;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Floating HUD readout of distance from the settlement's Town Hall Core while holding a Planned
 * Perimeter Stake (design doc Section 6 -- a real playtest request: no in-world way to judge
 * distance while walking the 500 ft/~152 block placement radius). Same shape as Lyfe's own
 * {@code location.LocationOverlay} (a persistent, mostly-solid-backed top overlay).
 *
 * <p><b>No permission check needed here</b> -- {@code GhostTownHallCoreEntity#broadcastToPlayer}
 * already means only cores this exact client is permitted to see are ever synced to it at all;
 * any instance found in {@link ClientLevel#entitiesForRendering()} is guaranteed visible to this
 * player already.
 *
 * <p><b>Measures from the targeted placement block, not the player's own body, 2026-10-05</b> (real
 * report: placing stakes for an exact-radius circle, "the number was changing as I moved past the
 * half-way point" -- this class's own doc used to claim it "matches how {@code
 * PlannedPerimeterStakeItem}'s own placement-radius check is computed," which had gone stale: that
 * check measures from the *clicked block's center* ({@code placePos.getX() + 0.5}, see its own
 * {@code findOwningCore}), not the player's continuous position, which this overlay used to show
 * instead -- the two could differ by up to ~0.7 blocks depending on exactly where the player was
 * standing versus aiming. Now reads the client's own current crosshair target ({@code
 * Minecraft#hitResult}) the same way {@code useOn} resolves {@code placePos} (one block above
 * whatever's targeted), so the number shown is the number that will actually gate/measure the stake
 * that would be placed right now -- falls back to the player's own position if nothing is targeted
 * (looking at the sky, out of range, etc.), same as before.
 *
 * <p><b>Widened 2026-10-09, explicit request</b> -- Plot Placement Stakes and Roadway Stakes now get
 * the same kind of live readout, but measured from whichever stake the player is currently attached
 * to (their ghost leash anchor) rather than the Town Hall, since those two mechanics build a chain/
 * graph of connections rather than one fixed-radius circle around a single core. The anchor's
 * position arrives via {@code AnchorDistancePayload} (see its own doc) into {@link
 * ClientAnchorDistanceState}, synced every ~10 ticks server-side -- plenty fresh since the anchor
 * itself is static once placed; only the player's own live position (read directly here, same as the
 * Town Hall case) needs to be continuous.
 */
public final class StakeDistanceOverlay implements GuiLayer {

    private static final int MARGIN = 6;
    private static final int PADDING = 4;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int BACKGROUND_COLOR = 0xE0101010;

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        double originX;
        double originZ;
        String label;
        if (isHoldingPerimeterStake(player)) {
            GhostTownHallCoreEntity nearestCore = findNearestCore(level, player);
            if (nearestCore == null) {
                return;
            }
            originX = nearestCore.getX();
            originZ = nearestCore.getZ();
            label = "Distance from Town Hall: ";
        } else if (isHoldingChainStake(player)) {
            Optional<BlockPos> anchorPos = ClientAnchorDistanceState.get();
            if (anchorPos.isEmpty()) {
                return;
            }
            originX = anchorPos.get().getX() + 0.5;
            originZ = anchorPos.get().getZ() + 0.5;
            label = "Distance from Stake: ";
        } else {
            return;
        }

        // 2D distance, ignoring Y, from the same point PlannedPerimeterStakeItem#useOn would actually
        // place at (one above whatever block is currently targeted) -- see this class's own doc.
        double targetX;
        double targetZ;
        HitResult hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos placePos = blockHit.getBlockPos().above();
            targetX = placePos.getX() + 0.5;
            targetZ = placePos.getZ() + 0.5;
        } else {
            targetX = player.getX();
            targetZ = player.getZ();
        }
        double dx = originX - targetX;
        double dz = originZ - targetZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        String text = label + Math.round(distance) + " blocks";

        Font font = minecraft.font;
        int textWidth = font.width(text);
        int left = guiGraphics.guiWidth() / 2 - textWidth / 2 - PADDING;
        int right = left + textWidth + PADDING * 2;
        int top = MARGIN;
        int bottom = top + font.lineHeight + PADDING * 2;

        guiGraphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
        guiGraphics.text(font, text, left + PADDING, top + PADDING, TEXT_COLOR);
    }

    private static boolean isHoldingPerimeterStake(Player player) {
        return player.getMainHandItem().is(ModItems.PLANNED_PERIMETER_STAKE.get())
                || player.getOffhandItem().is(ModItems.PLANNED_PERIMETER_STAKE.get());
    }

    private static boolean isHoldingChainStake(Player player) {
        return player.getMainHandItem().is(ModItems.PLOT_PLACEMENT_STAKE.get())
                || player.getMainHandItem().is(ModItems.ROADWAY_STAKE.get());
    }

    private static GhostTownHallCoreEntity findNearestCore(ClientLevel level, Player player) {
        GhostTownHallCoreEntity nearest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof GhostTownHallCoreEntity core)) {
                continue;
            }
            double dx = core.getX() - player.getX();
            double dz = core.getZ() - player.getZ();
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq < closestDistanceSq) {
                closestDistanceSq = distanceSq;
                nearest = core;
            }
        }
        return nearest;
    }
}
