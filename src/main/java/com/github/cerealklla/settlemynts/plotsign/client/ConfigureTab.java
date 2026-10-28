package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.nuclyus.menu.client.NuclyusMenu;
import com.github.cerealklla.nuclyus.menu.client.WidgetHost;
import com.github.cerealklla.settlemynts.mineshaft.MineLootTable;
import com.github.cerealklla.settlemynts.mineshaft.MineSpecialization;
import com.github.cerealklla.settlemynts.plotsign.ClientPlotSignRequests;
import com.github.cerealklla.settlemynts.plotsign.OpenConfigurePlotPayload;
import com.github.cerealklla.settlemynts.plotsign.OpenPlotConfigSignMenuPayload;
import com.github.cerealklla.settlemynts.plotsign.RelocatePlotSignPayload;
import com.github.cerealklla.settlemynts.plotsign.RequestConfigurePlotPayload;
import com.github.cerealklla.settlemynts.plotsign.SetMineSpecializationPayload;
import com.github.cerealklla.settlemynts.plotsign.SetPlantSelectionPayload;
import com.github.cerealklla.settlemynts.zone.WorkerPlantSelection;

import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * "Configure" tab (tabbed Plot Config Sign rework, 2026-10-23) -- only present when {@code
 * canManage}. Folds the old flat "Configure Plot" screen's "Relocate Plot Sign" button in as-is,
 * plus the new Mineshaft-only "Specialization" control (Owner/Mayor/Town Planners can switch a
 * Mineshaft plot between {@link MineSpecialization#ORE}/{@link MineSpecialization#STONE}/{@link
 * MineSpecialization#EXCAVATION} -- see {@code mineshaft.MineLootTable}'s own doc). The current specialization is fetched fresh via
 * {@link RequestConfigurePlotPayload} every time this tab is shown, same "always refetch" discipline
 * as every other server-backed tab here.
 */
public final class ConfigureTab implements NuclyusMenu {

    private final OpenPlotConfigSignMenuPayload data;
    private WidgetHost host;
    private OpenConfigurePlotPayload configured;
    private Button relocateButton;
    private Button oreButton;
    private Button stoneButton;
    private Button excavationButton;
    // Farm/Lumberyard Plant Selection, 2026-10-28 ("similar way to the Mine") -- one Set/Clear
    // button per slot, only as many as the plot's current Tier unlocks (WorkerPlantSelection
    // #MAX_SLOTS wide at most). Rebuilt in #onShown and every time a fresh OpenConfigurePlotPayload
    // arrives (slot count can change -- plot got Tier'd up while this tab was already open).
    private final List<Button> plantSlotButtons = new ArrayList<>();

    public ConfigureTab(OpenPlotConfigSignMenuPayload data) {
        this.data = data;
    }

    @Override
    public Component title() {
        return Component.literal("Configure");
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public void attachHost(Screen host) {
        this.host = (WidgetHost) host;
    }

    @Override
    public void onShown() {
        configured = null;
        if (data.isMineshaft() || data.isFarm() || data.isLumberyard()) {
            send(new RequestConfigurePlotPayload(data.signPos()));
        }

        Rect2i area = host.contentArea();
        int centerX = area.getX() + area.getWidth() / 2;
        int y = area.getY() + 20;
        relocateButton = host.addMenuWidget(Button.builder(Component.literal("Relocate Plot Sign"), b -> relocate())
                .bounds(centerX - 100, y, 200, 20).build());
        y += 30;

        if (data.isMineshaft()) {
            oreButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Ore"), b -> setSpecialization(MineSpecialization.ORE))
                    .bounds(centerX - 235, y, 150, 20).build());
            stoneButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Stone"), b -> setSpecialization(MineSpecialization.STONE))
                    .bounds(centerX - 75, y, 150, 20).build());
            excavationButton = host.addMenuWidget(Button.builder(Component.literal("Specialization: Excavation"), b -> setSpecialization(MineSpecialization.EXCAVATION))
                    .bounds(centerX + 85, y, 150, 20).build());
            refreshSpecializationButtons();
        }
        if (data.isFarm() || data.isLumberyard()) {
            buildPlantSlotButtons(area, y);
        }
    }

    @Override
    public void onHidden() {
        if (relocateButton != null) {
            host.removeMenuWidget(relocateButton);
            relocateButton = null;
        }
        if (oreButton != null) {
            host.removeMenuWidget(oreButton);
            oreButton = null;
        }
        if (stoneButton != null) {
            host.removeMenuWidget(stoneButton);
            stoneButton = null;
        }
        if (excavationButton != null) {
            host.removeMenuWidget(excavationButton);
            excavationButton = null;
        }
        for (Button button : plantSlotButtons) {
            host.removeMenuWidget(button);
        }
        plantSlotButtons.clear();
    }

    /**
     * One button per unlocked slot ({@link WorkerPlantSelection#slotsForTier}, {@code data.tier()}).
     * Each button's own label shows the slot's currently-configured item name (or "(empty)") -- the
     * real set/clear action is "hold the item you want (or hold nothing to clear) and click the
     * slot," per the approved spec, so there's no separate item-picker UI to build. Rebuilt whenever
     * a fresh {@link OpenConfigurePlotPayload} arrives, not just once in {@link #onShown}, since the
     * label needs the real current selections the server just sent.
     */
    private void buildPlantSlotButtons(Rect2i area, int startY) {
        for (Button button : plantSlotButtons) {
            host.removeMenuWidget(button);
        }
        plantSlotButtons.clear();
        int centerX = area.getX() + area.getWidth() / 2;
        int visibleSlots = WorkerPlantSelection.slotsForTier(data.tier());
        List<String> selections = configured != null ? configured.plantSelections() : WorkerPlantSelection.normalized(List.of());
        String kind = data.isFarm() ? "Seed" : "Sapling";
        for (int i = 0; i < visibleSlots; i++) {
            int slotIndex = i;
            String raw = i < selections.size() ? selections.get(i) : "";
            String label = "Slot " + (i + 1) + ": " + labelFor(raw, kind);
            int y = startY + i * 22;
            Button button = host.addMenuWidget(Button.builder(Component.literal(label), b -> setPlantSlot(slotIndex))
                    .bounds(centerX - 150, y, 300, 20).build());
            plantSlotButtons.add(button);
        }
    }

    private static String labelFor(String raw, String kind) {
        if (raw.isEmpty()) {
            return "(empty, hold a " + kind + " to set)";
        }
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(raw);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        return item == null || item == net.minecraft.world.item.Items.AIR
                ? "(unknown item)"
                : new ItemStack(item).getHoverName().getString();
    }

    /** Also re-requests fresh config (not just fire-and-forget) so the slot labels pick up the real new selection once the server replies -- see {@link #render}'s own widened re-check for Farm/Lumberyard. */
    private void setPlantSlot(int slotIndex) {
        send(new SetPlantSelectionPayload(data.signPos(), slotIndex));
        send(new RequestConfigurePlotPayload(data.signPos()));
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
        // Mineshaft only ever needs its first real response (nothing here changes it afterward);
        // Farm/Lumberyard keeps re-checking after every Set/Clear click re-requests fresh data (see
        // #setPlantSlot), so its own slot labels can refresh in place without reopening the tab.
        boolean keepChecking = data.isFarm() || data.isLumberyard();
        if (data.isMineshaft() || keepChecking) {
            if (configured == null || keepChecking) {
                ClientPlotSignRequests.takePendingConfigurePlot().ifPresent(payload -> {
                    configured = payload;
                    refreshSpecializationButtons();
                    if (keepChecking) {
                        buildPlantSlotButtons(area, area.getY() + 50);
                    }
                });
            }
        }
        if (data.isMineshaft() && configured != null) {
            renderFindOdds(graphics, area);
        }
    }

    // 5 pips per entry, 0 dots at 0% up to 5 dots at this table's own highest weight -- explicit
    // user spec, 2026-10-28: "The scale is 0% (0 dots) to the highest % available in this table (5
    // dots)." A relative rarity gauge, not an absolute percentage -- "highest" only means something
    // once every entry in the current tier+specialization table is in hand (see MineLootTable
    // #entriesFor's own doc).
    private static final int ODDS_COLUMNS = 4;
    private static final int PIP_COUNT = 5;
    private static final int PIP_SIZE = 6;
    private static final int PIP_GAP = 2;
    private static final int ROW_HEIGHT = 20;
    private static final int FILLED_COLOR = 0xFF55AAFF;
    private static final int EMPTY_COLOR = 0xFF888888;
    private static final int NEXT_TIER_CHANGE_COLOR = 0xFF55FF55;

    private void renderFindOdds(GuiGraphicsExtractor graphics, Rect2i area) {
        List<MineLootTable.Entry> entries = MineLootTable.entriesFor(configured.tier(), configured.specialization());
        if (entries.isEmpty()) {
            return;
        }
        int maxWeight = entries.get(0).weight(); // entriesFor is sorted highest-weight-first.

        int headerY = area.getY() + 95;
        graphics.text(Minecraft.getInstance().font,
                Component.literal("Mineshaft Finds -- Tier " + configured.tier() + ", " + configured.specialization().label() + ":"),
                area.getX() + 10, headerY, 0xFFFFFFFF);

        // Next-tier preview, explicit user request, 2026-10-28: a green border on any pip whose
        // filled/empty state is about to change at the next tier -- "Tier 5 would obviously have no
        // green boxes anywhere" falls out for free, since entriesFor's own tier-clamping makes
        // "tier + 1" resolve back to the same table at the max tier, so every diff is naturally zero.
        List<MineLootTable.Entry> nextEntries = MineLootTable.entriesFor(configured.tier() + 1, configured.specialization());
        int nextMaxWeight = nextEntries.stream().mapToInt(MineLootTable.Entry::weight).max().orElse(0);
        Map<Item, Integer> nextWeightByItem = new HashMap<>();
        for (MineLootTable.Entry nextEntry : nextEntries) {
            nextWeightByItem.put(nextEntry.item(), nextEntry.weight());
        }

        int gridY = headerY + 14;
        int columnWidth = area.getWidth() / ODDS_COLUMNS;
        for (int i = 0; i < entries.size(); i++) {
            MineLootTable.Entry entry = entries.get(i);
            int column = i % ODDS_COLUMNS;
            int row = i / ODDS_COLUMNS;
            int x = area.getX() + column * columnWidth + 10;
            int y = gridY + row * ROW_HEIGHT;

            graphics.item(new ItemStack(entry.item()), x, y);

            int dots = maxWeight <= 0 ? 0 : (int) Math.round(PIP_COUNT * (double) entry.weight() / maxWeight);
            int nextWeight = nextWeightByItem.getOrDefault(entry.item(), 0);
            int nextDots = nextMaxWeight <= 0 ? 0 : (int) Math.round(PIP_COUNT * (double) nextWeight / nextMaxWeight);
            int pipX = x + 20;
            int pipY = y + 5;
            for (int pip = 0; pip < PIP_COUNT; pip++) {
                int px = pipX + pip * (PIP_SIZE + PIP_GAP);
                boolean filled = pip < dots;
                boolean changing = filled != (pip < nextDots);
                if (filled) {
                    graphics.fill(px, pipY, px + PIP_SIZE, pipY + PIP_SIZE, FILLED_COLOR);
                } else {
                    graphics.outline(px, pipY, PIP_SIZE, PIP_SIZE, EMPTY_COLOR);
                }
                if (changing) {
                    graphics.outline(px, pipY, PIP_SIZE, PIP_SIZE, NEXT_TIER_CHANGE_COLOR);
                }
            }
        }
    }

    private void refreshSpecializationButtons() {
        if (configured == null || oreButton == null || stoneButton == null || excavationButton == null) {
            return;
        }
        oreButton.active = configured.specialization() != MineSpecialization.ORE;
        stoneButton.active = configured.specialization() != MineSpecialization.STONE;
        excavationButton.active = configured.specialization() != MineSpecialization.EXCAVATION;
    }

    private void relocate() {
        send(new RelocatePlotSignPayload(data.signPos()));
    }

    private void setSpecialization(MineSpecialization specialization) {
        send(new SetMineSpecializationPayload(data.signPos(), specialization));
        configured = new OpenConfigurePlotPayload(data.signPos(), specialization, configured == null ? 1 : configured.tier(),
                configured == null ? List.of() : configured.plantSelections());
        refreshSpecializationButtons();
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }
}
