package com.github.cerealklla.settlemynts.plotsign.client;

import com.github.cerealklla.settlemynts.plotsign.EstablishHighwayCandidatesPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestCreateHighwayQuestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * "Establish Highway" destination picker -- added 2026-10-13, explicit user spec: a zoomed portion
 * of the Mayor's own discovered map, showing every settlement they know about (including partially
 * known ones) within 2 real miles, clickable to select one (a green ring around the chosen dot),
 * with distance/stake-threshold details and a "Create Quest" button appearing once something's
 * selected. Deliberately a flat, self-contained map (no real terrain sampling) -- this is a
 * destination picker, not Lyfe's own full-screen Map (`map.client.MapScreen`), which this borrows
 * coordinate-math technique from rather than reusing directly (that screen is keybind-triggered,
 * Expeditionist-level-gated, and owns a persisted terrain cache this picker has no need for).
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
    private static final int VIEWPORT_SIZE = 260;
    private static final int DOT_HOVER_RADIUS_PX = 5;
    private static final int SELECTION_RING_RADIUS_PX = 6;

    private final EstablishHighwayCandidatesPayload data;
    private Long selectedPlaceId;
    private Button createQuestButton;

    private int viewportLeft;
    private int viewportTop;

    public EstablishHighwayMapScreen(EstablishHighwayCandidatesPayload data) {
        super(Component.literal("Establish Highway"));
        this.data = data;
    }

    @Override
    protected void init() {
        viewportLeft = width / 2 - VIEWPORT_SIZE - 20;
        viewportTop = 46;

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 - 50, viewportTop + VIEWPORT_SIZE + 20, 100, 20).build());

        rebuildActionArea();
    }

    private void rebuildActionArea() {
        if (createQuestButton != null) {
            removeWidget(createQuestButton);
            createQuestButton = null;
        }
        if (selectedPlaceId == null) {
            return;
        }
        int panelLeft = viewportLeft + VIEWPORT_SIZE + 30;
        createQuestButton = addRenderableWidget(Button.builder(Component.literal("Create Quest"), b -> createQuest())
                .bounds(panelLeft, viewportTop + VIEWPORT_SIZE - 24, 160, 20).build());
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
                rebuildActionArea();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private EstablishHighwayCandidatesPayload.Candidate findCandidateNear(int mouseX, int mouseY) {
        double pixelsPerBlock = VIEWPORT_SIZE / (2.0 * MAP_RADIUS_BLOCKS);
        int centerX = viewportLeft + VIEWPORT_SIZE / 2;
        int centerY = viewportTop + VIEWPORT_SIZE / 2;
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

        graphics.fill(viewportLeft, viewportTop, viewportLeft + VIEWPORT_SIZE, viewportTop + VIEWPORT_SIZE, 0xFF101010);

        double pixelsPerBlock = VIEWPORT_SIZE / (2.0 * MAP_RADIUS_BLOCKS);
        int centerX = viewportLeft + VIEWPORT_SIZE / 2;
        int centerY = viewportTop + VIEWPORT_SIZE / 2;

        // The Town Hall's own position -- always the map's center.
        drawDot(graphics, centerX, centerY, 0xFFFFFFFF);

        EstablishHighwayCandidatesPayload.Candidate hovered = findCandidateNear(mouseX, mouseY);
        for (EstablishHighwayCandidatesPayload.Candidate candidate : data.candidates()) {
            int screenX = centerX + (int) Math.round((candidate.x() - data.originX()) * pixelsPerBlock);
            int screenY = centerY + (int) Math.round((candidate.z() - data.originZ()) * pixelsPerBlock);
            if (screenX < viewportLeft || screenX > viewportLeft + VIEWPORT_SIZE
                    || screenY < viewportTop || screenY > viewportTop + VIEWPORT_SIZE) {
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

        int panelLeft = viewportLeft + VIEWPORT_SIZE + 30;
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
        graphics.text(font, selected.displayText(), panelLeft, panelY, 0xFFFFFFFF);
        panelY += 14;
        graphics.text(font, "Distance: " + distanceBlocks + " blocks", panelLeft, panelY, 0xFFAAAAAA);
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
