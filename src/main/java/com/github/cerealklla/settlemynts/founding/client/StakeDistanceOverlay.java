package com.github.cerealklla.settlemynts.founding.client;

import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.registration.ModItems;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
        if (player == null || level == null || !isHoldingStake(player)) {
            return;
        }

        GhostTownHallCoreEntity nearestCore = findNearestCore(level, player);
        if (nearestCore == null) {
            return;
        }

        // 2D distance, ignoring Y -- matches how PlannedPerimeterStakeItem's own placement-radius
        // check is computed, so this readout genuinely reflects the same number that gates placement.
        double dx = nearestCore.getX() - player.getX();
        double dz = nearestCore.getZ() - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        String text = "Distance from Town Hall: " + Math.round(distance) + " blocks";

        Font font = minecraft.font;
        int textWidth = font.width(text);
        int left = guiGraphics.guiWidth() / 2 - textWidth / 2 - PADDING;
        int right = left + textWidth + PADDING * 2;
        int top = MARGIN;
        int bottom = top + font.lineHeight + PADDING * 2;

        guiGraphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
        guiGraphics.text(font, text, left + PADDING, top + PADDING, TEXT_COLOR);
    }

    private static boolean isHoldingStake(Player player) {
        return player.getMainHandItem().is(ModItems.PLANNED_PERIMETER_STAKE.get())
                || player.getOffhandItem().is(ModItems.PLANNED_PERIMETER_STAKE.get());
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
