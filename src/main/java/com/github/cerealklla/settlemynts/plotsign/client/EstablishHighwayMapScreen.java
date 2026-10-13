package com.github.cerealklla.settlemynts.plotsign.client;

import com.mojang.blaze3d.platform.NativeImage;

import com.github.cerealklla.settlemynts.bridge.client.LyfeTerrainBridge;
import com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestCreateHighwayQuestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * "Establish Highway" destination picker -- added 2026-10-13, explicit user spec: a zoomed portion
 * of the Mayor's own discovered map, showing every settlement they know about (including partially
 * known ones) within 2 real miles, clickable to select one (a green ring around the chosen dot),
 * with distance/stake-threshold details and a "Create Quest" button appearing once something's
 * selected.
 *
 * <p>Reworked 2026-10-13 after real UI feedback: the map viewport now renders the Mayor's own real,
 * previously-explored terrain (via {@link LyfeTerrainBridge}, same persisted-cache technique Lyfe's
 * own full-screen Map uses, not a live sample) behind the candidate dots, instead of a flat black
 * square -- an unexplored column still just reads as empty/transparent, same as Lyfe's own Map. The
 * viewport also now scales with the window instead of a fixed 260px, and both buttons anchor
 * directly beneath it so the layout stays coherent at any resolution instead of one button pinned to
 * the screen's own center and the other to the viewport, independently of each other.
 *
 * <p>Candidates are pre-filtered/pre-computed entirely server-side (see {@link
 * EstablishHighwayCandidatesPayload}'s own doc) -- this screen does no business logic at all, only
 * rendering and selection, same "server decides, client just displays and lets you pick" discipline
 * every other screen in this package follows.
 */
public final class EstablishHighwayMapScreen extends Screen {

    // Matches the server's own candidate-search radius (bridge.LyfeKnownSettlementsBridge call site)
    // -- the map always shows this same real-world span regardless of how close candidates actually
    // are, so the scale never jumps around between candidate sets.
    private static final double MAP_RADIUS_BLOCKS = 2.0 * 1609.0;
    private static final double BLOCKS_PER_MILE = 1609.0;
    static final int VIEWPORT_SIZE_MAX = 320;
    private static final int VIEWPORT_SIZE_MIN = 150;
    private static final int PANEL_WIDTH = 190;
    private static final int DOT_HOVER_RADIUS_PX = 5;
    private static final int SELECTION_RING_RADIUS_PX = 6;

    private final EstablishHighwayCandidatesPayload data;
    private Long selectedPlaceId;
    private Button createQuestButton;
    private Button cancelButton;

    private int viewportSize;
    private int viewportLeft;
    private int viewportTop;
    private Identifier terrainTextureId;

    public EstablishHighwayMapScreen(EstablishHighwayCandidatesPayload data) {
        super(Component.literal("Establish Highway"));
        this.data = data;
    }

    @Override
    protected void init() {
        viewportSize = Math.max(VIEWPORT_SIZE_MIN,
                Math.min(VIEWPORT_SIZE_MAX, Math.min(width - PANEL_WIDTH - 60, height - 90)));
        viewportLeft = 20;
        viewportTop = 36;

        buildTerrainTexture();
        rebuildActionArea(viewportTop + viewportSize + 16);
    }

    /** Samples {@link LyfeTerrainBridge}'s persisted cache once into a static-sized image for this picker -- see this screen's own doc for why this replaced the old flat background fill. */
    private void buildTerrainTexture() {
        if (!LyfeTerrainBridge.isLoaded() || Minecraft.getInstance().level == null) {
            terrainTextureId = null;
            return;
        }
        var dimension = Minecraft.getInstance().level.dimension();
        double scaleBlocks = (2.0 * MAP_RADIUS_BLOCKS) / viewportSize;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, viewportSize, viewportSize, false);
        for (int col = 0; col < viewportSize; col++) {
            int blockX = data.originX() + (int) Math.round((col - viewportSize / 2.0) * scaleBlocks);
            for (int row = 0; row < viewportSize; row++) {
                int blockZ = data.originZ() + (int) Math.round((row - viewportSize / 2.0) * scaleBlocks);
                image.setPixelABGR(col, row, argbToAbgr(LyfeTerrainBridge.sampleArgb(dimension, blockX, blockZ)));
            }
        }
        terrainTextureId = EstablishHighwayTerrainTexture.upload(image);
    }

    /** {@code NativeImage#setPixelABGR} expects byte order opposite normal ARGB -- same channel swap Lyfe's own map renderer needs for the same reason. */
    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // Both buttons are rebuilt together (not just repositioned) whenever selection changes, since
    // the Cancel button's own X shifts to make room for Create Quest once something's selected --
    // anchored as a centered pair directly beneath the viewport, rather than one pinned to the
    // screen's own center independently of the other (the real "doesn't fit" layout bug).
    private void rebuildActionArea(int buttonY) {
        if (cancelButton != null) {
            removeWidget(cancelButton);
        }
        if (createQuestButton != null) {
            removeWidget(createQuestButton);
            createQuestButton = null;
        }
        if (selectedPlaceId == null) {
            cancelButton = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                    .bounds(width / 2 - 50, buttonY, 100, 20).build());
            return;
        }
        cancelButton = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 - 105, buttonY, 100, 20).build());
        createQuestButton = addRenderableWidget(Button.builder(Component.literal("Create Quest"), b -> createQuest())
                .bounds(width / 2 + 5, buttonY, 100, 20).build());
    }

    private void createQuest() {
        if (selectedPlaceId == null) {
            return;
        }
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(
                new RequestCreateHighwayQuestPayload(data.signPos(), selectedPlaceId)));
        onClose();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) {
            EstablishHighwayCandidatesPayload.Candidate hit = findCandidateNear((int) event.x(), (int) event.y());
            if (hit != null) {
                selectedPlaceId = hit.placeId();
                rebuildActionArea(viewportTop + viewportSize + 16);
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private EstablishHighwayCandidatesPayload.Candidate findCandidateNear(int mouseX, int mouseY) {
        double pixelsPerBlock = viewportSize / (2.0 * MAP_RADIUS_BLOCKS);
        int centerX = viewportLeft + viewportSize / 2;
        int centerY = viewportTop + viewportSize / 2;
        EstablishHighwayCandidatesPayload.Candidate nearest = null;
        double nearestDistSq = (DOT_HOVER_RADIUS_PX * DOT_HOVER_RADIUS_PX) + 1;
        for (EstablishHighwayCandidatesPayload.Candidate candidate : data.candidates()) {
            int screenX = centerX + (int) Math.round((candidate.x() - data.originX()) * pixelsPerBlock);
            int screenY = centerY + (int) Math.round((candidate.z() - data.originZ()) * pixelsPerBlock);
            double dx = mouseX - screenX;
            double dy = mouseY - screenY;
            double distSq = dx * dx + dy * dy;
            if (distSq <= DOT_HOVER_RADIUS_PX * DOT_HOVER_RADIUS_PX && distSq < nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 20, 0xFFFFFFFF);

        // A "nice border" frame around the viewport -- same technique Lyfe's own full-screen Map
        // uses (a 1px-larger white fill drawn first, then the terrain blitted on top of it).
        graphics.fill(viewportLeft - 1, viewportTop - 1, viewportLeft + viewportSize + 1, viewportTop + viewportSize + 1, 0xFFC6C6C6);
        if (terrainTextureId != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, terrainTextureId, viewportLeft, viewportTop, 0, 0,
                    viewportSize, viewportSize, viewportSize, viewportSize, viewportSize, viewportSize);
        } else {
            graphics.fill(viewportLeft, viewportTop, viewportLeft + viewportSize, viewportTop + viewportSize, 0xFF101010);
        }

        double pixelsPerBlock = viewportSize / (2.0 * MAP_RADIUS_BLOCKS);
        int centerX = viewportLeft + viewportSize / 2;
        int centerY = viewportTop + viewportSize / 2;

        // The Town Hall's own position -- always the map's center.
        drawDot(graphics, centerX, centerY, 0xFFFFFFFF);

        EstablishHighwayCandidatesPayload.Candidate hovered = findCandidateNear(mouseX, mouseY);
        for (EstablishHighwayCandidatesPayload.Candidate candidate : data.candidates()) {
            int screenX = centerX + (int) Math.round((candidate.x() - data.originX()) * pixelsPerBlock);
            int screenY = centerY + (int) Math.round((candidate.z() - data.originZ()) * pixelsPerBlock);
            if (screenX < viewportLeft || screenX > viewportLeft + viewportSize
                    || screenY < viewportTop || screenY > viewportTop + viewportSize) {
                continue; // Outside the viewport square despite being within the radial search -- corners of the circle vs. the square box.
            }
            int color = candidate.fullyKnown() ? 0xFF55FF55 : 0xFFFFAA00;
            drawDot(graphics, screenX, screenY, color);
            if (candidate.placeId() == selectedPlaceId()) {
                drawRing(graphics, screenX, screenY, SELECTION_RING_RADIUS_PX, 0xFF00FF00);
            }
        }

        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, Component.literal(hovered.displayText()
                    + (hovered.fullyKnown() ? "" : " (partially known)")), mouseX, mouseY);
        }

        int panelLeft = viewportLeft + viewportSize + 24;
        int panelY = viewportTop;
        if (selectedPlaceId == null) {
            graphics.text(font, "Select a settlement.", panelLeft, panelY, 0xFFAAAAAA);
            return;
        }
        EstablishHighwayCandidatesPayload.Candidate selected = data.candidates().stream()
                .filter(c -> c.placeId() == selectedPlaceId()).findFirst().orElse(null);
        if (selected == null) {
            return;
        }
        double dx = selected.x() - data.originX();
        double dz = selected.z() - data.originZ();
        int distanceBlocks = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        double distanceMiles = distanceBlocks / BLOCKS_PER_MILE;
        graphics.text(font, selected.displayText(), panelLeft, panelY, 0xFFFFFFFF);
        panelY += 14;
        graphics.text(font, String.format("Distance: %.1f Miles (%d blocks)", distanceMiles, distanceBlocks), panelLeft, panelY, 0xFFAAAAAA);
        panelY += 10;
        graphics.text(font, "Min Highway Stakes: " + selected.minStakes(), panelLeft, panelY, 0xFFAAAAAA);
        panelY += 10;
        graphics.text(font, "Stay under " + selected.optimalStakeCap() + " for a bonus", panelLeft, panelY, 0xFFAAAAAA);
    }

    private long selectedPlaceId() {
        return selectedPlaceId == null ? Long.MIN_VALUE : selectedPlaceId;
    }

    private void drawDot(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x - 2, y - 2, x + 2, y + 2, color);
    }

    private void drawRing(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int color) {
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            double angle = (Math.PI * 2 * i) / steps;
            int px = centerX + (int) Math.round(Math.cos(angle) * radius);
            int py = centerY + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(px - 1, py - 1, px + 1, py + 1, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
