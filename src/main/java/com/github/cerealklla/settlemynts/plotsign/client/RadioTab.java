package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenMurmurRadioPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestMurmurRadioPayload;
import com.github.cerealklla.settlemynts.plotsign.SetMurmurPlaylistPayload;
import com.github.cerealklla.settlemynts.plotsign.SubmitSongRequestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Radio" tab -- only present when {@code canManage && isMurmurTower}. Two sections: a scrollable
 * catalog picker (toggle which of Audyble's known tracks this settlement's station plays) and, if
 * Audyble has a request file configured, a plain text box to submit a YouTube link for the nightly
 * external job to download and assign. Same scissor-clip/row-widget-only-while-visible pattern as
 * {@code ShopTab}'s Manage Shop page, with toggle {@link Button}s in place of that page's
 * price {@link EditBox}es.
 */
public final class RadioTab implements NuclyusMenu {

    private static final int ROW_HEIGHT = 22;
    private static final int PANEL_HALF_WIDTH = 220;

    private final OpenPlotConfigSignMenuPayload data;
    private WidgetHost host;

    private OpenMurmurRadioPayload radioData;
    private final Set<String> selectedTrackIds = new LinkedHashSet<>();
    private final List<Button> rowWidgets = new ArrayList<>();
    private int scrollOffset;
    private Button saveButton;

    private EditBox requestBox;
    private Button submitButton;
    private String confirmationMessage = "";

    public RadioTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Radio");
    }

    @Override
    public int order() {
        return 6;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        radioData = null;
        selectedTrackIds.clear();
        scrollOffset = 0;
        confirmationMessage = "";
        send(new RequestMurmurRadioPayload(data.signPos()));
    }

    @Override
    public void onHidden() {
        clearRowWidgets();
        removeSaveButton();
        removeRequestWidgets();
    }

    private int viewportTop(Rect2i area) {
        return area.getY() + 24;
    }

    /** Same content-sized-footer approach as {@code ShopTab#manageFooterTop} -- see that method's own doc. */
    private int footerTop(Rect2i area) {
        int maxFooter = area.getY() + area.getHeight() - 70;
        if (radioData == null) {
            return maxFooter;
        }
        int rowCount = Math.max(1, radioData.catalog().size());
        return Math.min(maxFooter, viewportTop(area) + rowCount * ROW_HEIGHT);
    }

    private int maxScrollOffset(Rect2i area) {
        if (radioData == null) {
            return 0;
        }
        int viewport = footerTop(area) - viewportTop(area);
        return Math.max(0, radioData.catalog().size() * ROW_HEIGHT - viewport);
    }

    private void clearRowWidgets() {
        for (Button button : rowWidgets) {
            host.removeMenuWidget(button);
        }
        rowWidgets.clear();
    }

    private void removeSaveButton() {
        if (saveButton != null) {
            host.removeMenuWidget(saveButton);
            saveButton = null;
        }
    }

    private void removeRequestWidgets() {
        if (requestBox != null) {
            host.removeMenuWidget(requestBox);
            requestBox = null;
        }
        if (submitButton != null) {
            host.removeMenuWidget(submitButton);
            submitButton = null;
        }
    }

    private void rebuildRows(Rect2i area) {
        clearRowWidgets();
        if (radioData == null) {
            return;
        }
        int centerX = area.getX() + area.getWidth() / 2;
        int top = viewportTop(area);
        int bottom = footerTop(area);
        List<OpenMurmurRadioPayload.CatalogEntry> catalog = radioData.catalog();
        for (int i = 0; i < catalog.size(); i++) {
            OpenMurmurRadioPayload.CatalogEntry entry = catalog.get(i);
            int rowY = top + i * ROW_HEIGHT - scrollOffset;
            if (rowY < top || rowY + ROW_HEIGHT > bottom) {
                continue;
            }
            boolean selected = selectedTrackIds.contains(entry.trackId());
            Button toggle = Button.builder(rowLabel(entry, selected), b -> toggleTrack(entry.trackId(), area))
                    .bounds(centerX - PANEL_HALF_WIDTH + 10, rowY, PANEL_HALF_WIDTH * 2 - 20, 20).build();
            host.addMenuWidget(toggle);
            rowWidgets.add(toggle);
        }
    }

    private static Component rowLabel(OpenMurmurRadioPayload.CatalogEntry entry, boolean selected) {
        return Component.literal((selected ? "[x] " : "[ ] ") + entry.artist() + " - " + entry.song());
    }

    private void toggleTrack(String trackId, Rect2i area) {
        if (!selectedTrackIds.remove(trackId)) {
            selectedTrackIds.add(trackId);
        }
        rebuildRows(area);
    }

    private void addRequestWidgets(Rect2i area) {
        if (radioData == null || !radioData.songRequestsEnabled()) {
            return;
        }
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + area.getHeight() - 46;
        requestBox = new EditBox(Minecraft.getInstance().font, centerX - 150, y, 220, 20, Component.literal("YouTube URL"));
        requestBox.setMaxLength(512);
        host.addMenuWidget(requestBox);
        submitButton = host.addMenuWidget(Button.builder(Component.literal("Submit Request"), b -> submitRequest())
                .bounds(centerX + 80, y, 100, 20).build());
    }

    private void submitRequest() {
        if (requestBox == null) {
            return;
        }
        String url = requestBox.getValue().trim();
        if (url.isEmpty()) {
            return;
        }
        send(new SubmitSongRequestPayload(data.signPos(), url));
        requestBox.setValue("");
        confirmationMessage = "Request submitted.";
    }

    private void saveChanges() {
        send(new SetMurmurPlaylistPayload(data.signPos(), List.copyOf(selectedTrackIds)));
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        if (radioData == null) {
            ClientPlotSignRequests.takePendingMurmurRadio().ifPresent(payload -> {
                radioData = payload;
                selectedTrackIds.addAll(payload.currentPlaylist());
                rebuildRows(area);
                removeSaveButton();
                int centerX = area.getX() + area.getWidth() / 2;
                int footer = footerTop(area);
                saveButton = host.addMenuWidget(Button.builder(Component.literal("Save Playlist"), b -> saveChanges())
                        .bounds(centerX - 75, footer + 10, 150, 20).build());
                addRequestWidgets(area);
            });
        }

        Font font = Minecraft.getInstance().font;
        int centerX = area.getX() + area.getWidth() / 2;
        int top = viewportTop(area);
        if (radioData == null) {
            String message = "Loading...";
            graphics.text(font, message, centerX - font.width(message) / 2, top, 0xFFAAAAAA);
            return;
        }

        int bottom = footerTop(area);
        graphics.fill(centerX - PANEL_HALF_WIDTH, top - 16, centerX + PANEL_HALF_WIDTH, bottom + 4, 0xC0101010);
        String header = "Station Playlist (empty = play everything available)";
        graphics.text(font, header, centerX - font.width(header) / 2, top - 14, 0xFFAAAAAA);

        List<OpenMurmurRadioPayload.CatalogEntry> catalog = radioData.catalog();
        if (catalog.isEmpty()) {
            String message = "No music found on the server yet.";
            graphics.text(font, message, centerX - font.width(message) / 2, top, 0xFFAAAAAA);
        }

        if (radioData.songRequestsEnabled() && !confirmationMessage.isEmpty()) {
            int y = area.getY() + area.getHeight() - 60;
            graphics.text(font, confirmationMessage, centerX - font.width(confirmationMessage) / 2, y, 0xFF55FF55);
        } else if (radioData.songRequestsEnabled()) {
            int y = area.getY() + area.getHeight() - 60;
            String hint = "Paste a YouTube link to request a song:";
            graphics.text(font, hint, centerX - font.width(hint) / 2, y, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean mouseScrolled(Rect2i area, double mouseX, double mouseY, double scrollX, double scrollY) {
        if (radioData != null) {
            scrollOffset = Math.max(0, Math.min(maxScrollOffset(area), scrollOffset - (int) (scrollY * ROW_HEIGHT)));
            rebuildRows(area);
            return true;
        }
        return false;
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
