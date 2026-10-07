package com.github.cerealklla.settlemynts.zone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.cerealklla.cartographyr.geo.PlotValidity;
import com.github.cerealklla.settlemynts.registration.ModItems;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.GhostRoadAccessFlagEntity;
import com.github.cerealklla.settlemynts.zone.PlotGeometry;
import com.github.cerealklla.settlemynts.zone.PlotSessionData;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Persistent, every-frame HUD readout of live plot validity while holding a Plot Placement Stake
 * for an in-progress plot session (explicit user request, 2026-09-29 -- the one-shot actionbar
 * message previously sent on each stake add/remove wasn't persistent enough; this replaced it
 * entirely). Positioned just above the hotbar/health/food row (unlike {@code
 * StakeDistanceOverlay}'s near-the-top placement) since this is meant to be glanced at constantly
 * while walking the plot's shape out, not just occasionally.
 *
 * <p>Zero server round-trips needed for either half of this, same trick {@code
 * StakeDistanceOverlay} already uses: {@link GhostPlotStakeEntity#broadcastToPlayer} means only
 * stakes this client is permitted to see are ever synced to it at all, so {@code
 * entitiesForRendering()} is a safe source; and {@link PlotSessionData} travels on the held item
 * stack itself (a data component, already synced as part of normal inventory sync).
 */
public final class PlotValidityOverlay implements GuiLayer {

    private static final int MARGIN_FROM_BOTTOM = 52; // above the hotbar/health/food row
    private static final int PADDING = 4;
    private static final int VALID_COLOR = 0xFF55FF55;
    private static final int INVALID_COLOR = 0xFFFF5555;
    private static final int BACKGROUND_COLOR = 0xE0101010;

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        PlotSessionData session = resolveSession(player);
        // Plot Stakes rework (2026-09-30): plotSessionId (CurrentPlotID) is now blank by default and
        // between placements -- nothing to show until the item has actually placed or resumed a plot.
        if (session == null || session.plotSessionId() == null) {
            return;
        }
        java.util.UUID plotSessionId = session.plotSessionId();

        List<GhostPlotStakeEntity> stakes = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof GhostPlotStakeEntity stake && plotSessionId.equals(stake.getPlotSessionId())) {
                stakes.add(stake);
            }
        }
        stakes.sort(Comparator.comparingInt(GhostPlotStakeEntity::getPlacementIndex));

        boolean validArea = false;
        if (stakes.size() >= 3) {
            List<PlotGeometry.StakePoint> ordered = new ArrayList<>(stakes.size());
            for (GhostPlotStakeEntity stake : stakes) {
                ordered.add(new PlotGeometry.StakePoint(stake.getX(), stake.getZ()));
            }
            validArea = PlotValidity.hasValidArea(PlotGeometry.polygonFromStakes(ordered));
        }

        boolean hasRoadAccessFlag = false;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof GhostRoadAccessFlagEntity flag && plotSessionId.equals(flag.getPlotSessionId())) {
                hasRoadAccessFlag = true;
                break;
            }
        }

        boolean valid = validArea && hasRoadAccessFlag;
        String text;
        if (!validArea) {
            text = "⚠ No 15x15 buildable area yet";
        } else if (!hasRoadAccessFlag) {
            text = "⚠ Place the Road Access Flag on the perimeter";
        } else {
            text = "✓ Valid plot -- ready to finalize";
        }
        int textColor = valid ? VALID_COLOR : INVALID_COLOR;

        Font font = minecraft.font;
        int textWidth = font.width(text);
        int left = guiGraphics.guiWidth() / 2 - textWidth / 2 - PADDING;
        int right = left + textWidth + PADDING * 2;
        int bottom = guiGraphics.guiHeight() - MARGIN_FROM_BOTTOM;
        int top = bottom - font.lineHeight - PADDING * 2;

        guiGraphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
        guiGraphics.text(font, text, left + PADDING, top + PADDING, textColor);
    }

    private static PlotSessionData resolveSession(Player player) {
        ItemStack main = player.getMainHandItem();
        PlotSessionData session = main.get(ModItems.PLOT_SESSION_DATA);
        if (session != null) {
            return session;
        }
        return player.getOffhandItem().get(ModItems.PLOT_SESSION_DATA);
    }
}
