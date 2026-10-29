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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
    // Farm/Lumberyard Plant Selection, reworked 2026-10-28 -- real user feedback on the original
    // "hold the item and click the slot" flow ("This concept is fucking terrible... why would i want
    // to have to open the GUI from scratch for every single config"): replaced with a clickable
    // icon-grid picker per slot, matching Wainwright Station's own species selector exactly (same
    // icon size/gap/selection-highlight/tooltip mechanics) -- no items need to be in hand at all,
    // every carried Seed/Sapling is just a clickable icon. One row per unlocked slot (WorkerPlantSelection
    // #MAX_SLOTS wide at most); a leading "Clear" icon (vanilla Barrier, the suite's existing "none"
    // convention) lets a slot be emptied the same way. Purely render/click-driven, no Button widgets.
    private static final int PLANT_ICON_SIZE = 16;
    private static final int PLANT_ICON_GAP = 2;
    private static final int PLANT_ICON_CELL = PLANT_ICON_SIZE + PLANT_ICON_GAP;
    private static final int PLANT_GRID_COLUMNS = 10;
    private static final int PLANT_ROW_HEIGHT = PLANT_ICON_CELL + 12;
    private static final int PLANT_SELECTION_COLOR = 0xFFFFFFFF;

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
    }

    /** Every item the player is currently carrying (main inventory only, matching what the server will actually consume from) that's a real Seed or Sapling. */
    private List<Item> carriedPlantItems() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return List.of();
        }
        var tag = data.isFarm() ? ItemTags.VILLAGER_PLANTABLE_SEEDS : ItemTags.SAPLINGS;
        List<Item> found = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(tag) && !found.contains(stack.getItem())) {
                found.add(stack.getItem());
            }
        }
        return found;
    }

    /** Draws one slot's label + icon-grid row(s) and handles its own tooltip -- {@link #plantSlotGridTop} gives the matching click-hit math the identical layout. */
    private void renderPlantSlot(GuiGraphicsExtractor graphics, Rect2i area, int slotIndex, int startY, List<Item> carried, String raw, int mouseX, int mouseY) {
        int left = area.getX() + 10;
        graphics.text(Minecraft.getInstance().font, Component.literal("Slot " + (slotIndex + 1) + ":"), left, startY, 0xFFFFFFFF, false);
        int gridTop = startY + 11;

        // Cell 0 is always "Clear" (vanilla Barrier, this suite's established "none" icon).
        drawPlantCell(graphics, left, gridTop, 0, new ItemStack(Items.BARRIER), raw.isEmpty(), "Clear", mouseX, mouseY);
        for (int i = 0; i < carried.size(); i++) {
            Item item = carried.get(i);
            boolean selected = BuiltInRegistries.ITEM.getKey(item).toString().equals(raw);
            drawPlantCell(graphics, left, gridTop, i + 1, new ItemStack(item), selected, new ItemStack(item).getHoverName().getString(), mouseX, mouseY);
        }
    }

    private void drawPlantCell(GuiGraphicsExtractor graphics, int gridLeft, int gridTop, int cellIndex, ItemStack icon, boolean selected, String tooltip, int mouseX, int mouseY) {
        int col = cellIndex % PLANT_GRID_COLUMNS;
        int row = cellIndex / PLANT_GRID_COLUMNS;
        int cellX = gridLeft + col * PLANT_ICON_CELL;
        int cellY = gridTop + row * PLANT_ICON_CELL;
        if (selected) {
            graphics.fill(cellX - 1, cellY - 1, cellX + PLANT_ICON_SIZE + 1, cellY + PLANT_ICON_SIZE + 1, PLANT_SELECTION_COLOR);
        }
        graphics.item(icon, cellX, cellY);
        if (mouseX >= cellX && mouseX < cellX + PLANT_ICON_SIZE && mouseY >= cellY && mouseY < cellY + PLANT_ICON_SIZE) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, Component.literal(tooltip), mouseX, mouseY);
        }
    }

    /** Same layout {@link #renderPlantSlot} just drew, resolving which cell (if any) {@code (mouseX, mouseY)} landed on -- {@code -1} for "Clear," {@code >= 0} as an index into {@code carried}, {@code null} for a miss. */
    private Integer plantCellAt(Rect2i area, int startY, List<Item> carried, double mouseX, double mouseY) {
        int left = area.getX() + 10;
        int gridTop = startY + 11;
        int totalCells = 1 + carried.size();
        for (int cellIndex = 0; cellIndex < totalCells; cellIndex++) {
            int col = cellIndex % PLANT_GRID_COLUMNS;
            int row = cellIndex / PLANT_GRID_COLUMNS;
            int cellX = left + col * PLANT_ICON_CELL;
            int cellY = gridTop + row * PLANT_ICON_CELL;
            if (mouseX >= cellX && mouseX < cellX + PLANT_ICON_SIZE && mouseY >= cellY && mouseY < cellY + PLANT_ICON_SIZE) {
                return cellIndex - 1;
            }
        }
        return null;
    }

    private int plantSlotRowHeight(int carriedCount) {
        int totalCells = 1 + carriedCount;
        int rows = (totalCells + PLANT_GRID_COLUMNS - 1) / PLANT_GRID_COLUMNS;
        return 11 + rows * PLANT_ICON_CELL + 6;
    }

    /** Also re-requests fresh config (not just fire-and-forget) so the grid's highlight picks up the real new selection once the server replies -- see {@link #render}'s own widened re-check for Farm/Lumberyard. */
    private void setPlantSlot(int slotIndex, String itemId) {
        send(new SetPlantSelectionPayload(data.signPos(), slotIndex, itemId));
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
                });
            }
        }
        if (data.isMineshaft() && configured != null) {
            renderFindOdds(graphics, area);
        }
        if (keepChecking) {
            int visibleSlots = WorkerPlantSelection.slotsForTier(data.tier());
            List<String> selections = configured != null ? configured.plantSelections() : WorkerPlantSelection.normalized(List.of());
            List<Item> carried = carriedPlantItems();
            int rowHeight = plantSlotRowHeight(carried.size());
            int y = area.getY() + 50;
            for (int i = 0; i < visibleSlots; i++) {
                String raw = i < selections.size() ? selections.get(i) : "";
                renderPlantSlot(graphics, area, i, y, carried, raw, mouseX, mouseY);
                y += rowHeight;
            }
        }
    }

    @Override
    public boolean mouseClicked(Rect2i area, MouseButtonEvent event, boolean doubleClick) {
        if (!(data.isFarm() || data.isLumberyard())) {
            return false;
        }
        int visibleSlots = WorkerPlantSelection.slotsForTier(data.tier());
        List<Item> carried = carriedPlantItems();
        int rowHeight = plantSlotRowHeight(carried.size());
        int y = area.getY() + 50;
        for (int i = 0; i < visibleSlots; i++) {
            Integer cell = plantCellAt(area, y, carried, event.x(), event.y());
            if (cell != null) {
                String itemId = cell < 0 ? "" : BuiltInRegistries.ITEM.getKey(carried.get(cell)).toString();
                setPlantSlot(i, itemId);
                return true;
            }
            y += rowHeight;
        }
        return false;
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
