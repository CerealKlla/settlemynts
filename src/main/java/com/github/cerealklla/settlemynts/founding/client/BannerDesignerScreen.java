package com.github.cerealklla.settlemynts.founding.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * A from-scratch banner pattern designer (backlog item 10, added 2026-11-13, explicit user
 * choice: a real custom UI replicating vanilla's Loom layering, rather than requiring a real
 * Loom/banner item to be brought to the Keep). Reusable for any of {@code
 * ColorAndBannerScreen}'s "Design ... Banner..." buttons (Settlement, Capital City, or Kingdom --
 * backlog items 10/11) -- {@code commitFactory} constructs whichever payload actually writes the
 * finished design back ({@code SetSettlementBannerPayload}/{@code SetKingdomBannerPayload}).
 *
 * <p>The draft layer list lives purely client-side until "Save" -- same "client builds, server only
 * sees the final committed state" shape as every other free-form editor in this suite (no per-layer
 * round trip). The live preview is a real {@link BannerBlockEntity}-shaped {@link ItemStack} (the
 * matching base-color banner item, with {@code DataComponents.BANNER_PATTERNS} set to the current
 * draft) rendered the exact same way any inventory slot renders a held banner -- no custom 3D
 * rendering code needed, since vanilla's own special item model already does this.
 *
 * <p>Available patterns are enumerated from {@code Registries.BANNER_PATTERN} at {@link #init()}
 * time (a real datapack-driven registry, not a hardcoded list -- survives any future datapack
 * adding more) via the connected level's own registry access, the same lookup a server would use.
 *
 * <p>Redesigned into a two-column layout, 2026-11-13, from a user-supplied mockup: a left "Design"
 * column (the composed-banner preview, Save/Wipe/Cancel) and a right column (a left/right-arrow
 * Pattern preview showing the candidate next layer, a direct 4x4 {@link DyeColor} swatch grid --
 * exactly 16 colors, no cycling needed -- and Add/Undo). The old single "Pattern: N/M" text button
 * and "Color: white" cycle button are both gone, replaced by direct-manipulation controls.
 */
public final class BannerDesignerScreen extends Screen {

    /** Constructs whichever payload actually commits the finished design -- {@code SetSettlementBannerPayload::new}/{@code SetKingdomBannerPayload::new}. */
    public interface CommitFactory {
        CustomPacketPayload create(int coreEntityId, BannerPatternLayers patterns);
    }

    private final Screen parent;
    private final int coreEntityId;
    private final DyeColor baseColor;
    private final CommitFactory commitFactory;
    private final List<BannerPatternLayers.Layer> draft = new ArrayList<>();
    private List<Holder<BannerPattern>> availablePatterns = List.of();
    private int patternIndex;
    private DyeColor layerColor = DyeColor.WHITE;

    private Button addButton;

    /** The main (left-column) composed-banner preview -- bigger than the pattern preview, it's the headline result. */
    private static final int MAIN_PREVIEW_SIZE = 72;
    private static final float MAIN_PREVIEW_SCALE = MAIN_PREVIEW_SIZE / 16.0F;
    /** The right-column "what would Add produce" preview, flanked by Prev/Next arrows. */
    private static final int PATTERN_PREVIEW_SIZE = 48;
    private static final float PATTERN_PREVIEW_SCALE = PATTERN_PREVIEW_SIZE / 16.0F;
    private static final int PATTERN_PREVIEW_PANEL_PAD = 4;
    private static final int SWATCH_SIZE = 24;
    private static final int SWATCH_GAP = 4;
    private static final int SWATCH_COLUMNS = 4;

    private int leftColCenterX;
    private int rightColCenterX;
    private int mainPreviewTop;
    private int patternPreviewTop;
    private int colorGridTop;

    public BannerDesignerScreen(Screen parent, int coreEntityId, DyeColor baseColor, BannerPatternLayers current, CommitFactory commitFactory) {
        super(Component.literal("Banner Designer"));
        this.parent = parent;
        this.coreEntityId = coreEntityId;
        this.baseColor = baseColor == null ? DyeColor.WHITE : baseColor;
        this.commitFactory = commitFactory;
        this.draft.addAll(current.layers());
    }

    @Override
    protected void init() {
        if (minecraft != null && minecraft.level != null) {
            availablePatterns = minecraft.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)
                    .listElements().<Holder<BannerPattern>>map(ref -> ref).toList();
        }

        int centerX = width / 2;
        leftColCenterX = centerX - 110;
        rightColCenterX = centerX + 110;

        // ---- Left column: "Design" -- the composed-banner preview, Save/Wipe/Cancel ----
        mainPreviewTop = 46;
        int headerY = mainPreviewTop + MAIN_PREVIEW_SIZE + 14;
        int saveWipeY = headerY + 20;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(leftColCenterX - 70, saveWipeY, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Wipe"), b -> wipe())
                .bounds(leftColCenterX + 2, saveWipeY, 68, 20).build());
        int cancelY = saveWipeY + 24;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(leftColCenterX - 50, cancelY, 100, 20).build());

        // ---- Right column: Pattern preview + arrows, Color swatch grid, Add/Undo ----
        patternPreviewTop = 46;
        int arrowSize = PATTERN_PREVIEW_SIZE + PATTERN_PREVIEW_PANEL_PAD * 2;
        int arrowY = patternPreviewTop - PATTERN_PREVIEW_PANEL_PAD;
        int panelHalf = PATTERN_PREVIEW_SIZE / 2 + PATTERN_PREVIEW_PANEL_PAD;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cyclePattern(-1))
                .bounds(rightColCenterX - panelHalf - 4 - 20, arrowY, 20, arrowSize).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cyclePattern(1))
                .bounds(rightColCenterX + panelHalf + 4, arrowY, 20, arrowSize).build());

        int patternIndexLabelY = patternPreviewTop + PATTERN_PREVIEW_SIZE + PATTERN_PREVIEW_PANEL_PAD + 6;
        int colorLabelY = patternIndexLabelY + 14;
        colorGridTop = colorLabelY + 10;

        int gridWidth = SWATCH_COLUMNS * SWATCH_SIZE + (SWATCH_COLUMNS - 1) * SWATCH_GAP;
        int gridStartX = rightColCenterX - gridWidth / 2;
        DyeColor[] colors = DyeColor.values();
        for (int i = 0; i < colors.length; i++) {
            int col = i % SWATCH_COLUMNS;
            int row = i / SWATCH_COLUMNS;
            int x = gridStartX + col * (SWATCH_SIZE + SWATCH_GAP);
            int y = colorGridTop + row * (SWATCH_SIZE + SWATCH_GAP);
            addRenderableWidget(createColorSwatch(colors[i], x, y));
        }

        int rows = (colors.length + SWATCH_COLUMNS - 1) / SWATCH_COLUMNS;
        int addUndoY = colorGridTop + rows * (SWATCH_SIZE + SWATCH_GAP) - SWATCH_GAP + 10;
        addButton = addRenderableWidget(Button.builder(Component.literal("Add"), b -> addLayer())
                .bounds(rightColCenterX - 70, addUndoY, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Undo"), b -> removeLastLayer())
                .bounds(rightColCenterX + 2, addUndoY, 68, 20).build());
        updateAddButtonActive();
    }

    private Button createColorSwatch(DyeColor color, int x, int y) {
        Supplier<DyeColor> selected = () -> layerColor;
        return Button.builder(Component.literal(color.getName()), b -> selectLayerColor(color))
                .bounds(x, y, SWATCH_SIZE, SWATCH_SIZE)
                .build(builder -> new ColorSwatchButton(builder, color, selected));
    }

    /** A plain filled color square, no vanilla button sprite -- {@code extractContents} draws its own border/fill instead of calling {@code extractDefaultSprite}. */
    private static final class ColorSwatchButton extends Button {
        private final DyeColor swatchColor;
        private final Supplier<DyeColor> selectedColor;

        private ColorSwatchButton(Button.Builder builder, DyeColor swatchColor, Supplier<DyeColor> selectedColor) {
            super(builder);
            this.swatchColor = swatchColor;
            this.selectedColor = selectedColor;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = selectedColor.get() == swatchColor;
            int borderColor = selected ? 0xFFFFFFFF : (isHovered ? 0xFFAAAAAA : 0xFF555555);
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), borderColor);
            int fill = 0xFF000000 | swatchColor.getFireworkColor();
            graphics.fill(getX() + 2, getY() + 2, getX() + getWidth() - 2, getY() + getHeight() - 2, fill);
        }
    }

    private void selectLayerColor(DyeColor color) {
        layerColor = color;
    }

    private void cyclePattern(int delta) {
        if (availablePatterns.isEmpty()) {
            return;
        }
        int size = availablePatterns.size();
        patternIndex = ((patternIndex + delta) % size + size) % size;
    }

    private void addLayer() {
        if (availablePatterns.isEmpty() || draft.size() >= BannerBlockEntity.MAX_PATTERNS) {
            return;
        }
        draft.add(new BannerPatternLayers.Layer(availablePatterns.get(patternIndex), layerColor));
        updateAddButtonActive();
    }

    private void removeLastLayer() {
        if (!draft.isEmpty()) {
            draft.remove(draft.size() - 1);
            updateAddButtonActive();
        }
    }

    private void wipe() {
        if (!draft.isEmpty()) {
            draft.clear();
            updateAddButtonActive();
        }
    }

    private void updateAddButtonActive() {
        if (addButton != null) {
            addButton.active = draft.size() < BannerBlockEntity.MAX_PATTERNS && !availablePatterns.isEmpty();
        }
    }

    private void save() {
        send(commitFactory.create(coreEntityId, new BannerPatternLayers(List.copyOf(draft))));
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void send(CustomPacketPayload payload) {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
    }

    private ItemStack previewStack() {
        Identifier bannerId = Identifier.withDefaultNamespace(baseColor.getName() + "_banner");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getOptional(bannerId).orElse(net.minecraft.world.item.Items.WHITE_BANNER));
        stack.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, new BannerPatternLayers(List.copyOf(draft)));
        return stack;
    }

    /** Draft + the currently-selected Pattern/Color as an un-committed extra layer, for the "what would Add produce" preview. */
    private ItemStack candidatePreviewStack() {
        Identifier bannerId = Identifier.withDefaultNamespace(baseColor.getName() + "_banner");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getOptional(bannerId).orElse(net.minecraft.world.item.Items.WHITE_BANNER));
        List<BannerPatternLayers.Layer> candidateLayers = new ArrayList<>(draft);
        if (!availablePatterns.isEmpty() && candidateLayers.size() < BannerBlockEntity.MAX_PATTERNS) {
            candidateLayers.add(new BannerPatternLayers.Layer(availablePatterns.get(patternIndex), layerColor));
        }
        stack.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, new BannerPatternLayers(List.copyOf(candidateLayers)));
        return stack;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Matches nuclyus.menu.client.NuclyusMenuScreen's own backdrop -- see this class's own doc
        // on the whole Town Hall Core screen family being restyled to the same look, 2026-11-13.
        graphics.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, 10, 0xFFFFFFFF);

        // ---- Left column ----
        String designLabel = "Design";
        graphics.text(font, designLabel, leftColCenterX - font.width(designLabel) / 2, mainPreviewTop - 14, 0xFF888888);

        int mainPanelPad = 6;
        graphics.fill(leftColCenterX - MAIN_PREVIEW_SIZE / 2 - mainPanelPad, mainPreviewTop - mainPanelPad,
                leftColCenterX + MAIN_PREVIEW_SIZE / 2 + mainPanelPad, mainPreviewTop + MAIN_PREVIEW_SIZE + mainPanelPad, 0xFF2B2B2B);
        graphics.pose().pushMatrix();
        graphics.pose().translate(leftColCenterX - MAIN_PREVIEW_SIZE / 2.0F, mainPreviewTop);
        graphics.pose().scale(MAIN_PREVIEW_SCALE, MAIN_PREVIEW_SCALE);
        graphics.item(previewStack(), 0, 0);
        graphics.pose().popMatrix();

        int headerY = mainPreviewTop + MAIN_PREVIEW_SIZE + 14;
        String header = draft.isEmpty()
                ? "No layers yet (blank banner)"
                : "Layers: " + draft.size() + "/" + BannerBlockEntity.MAX_PATTERNS;
        graphics.text(font, header, leftColCenterX - font.width(header) / 2, headerY, 0xFFAAAAAA);

        // ---- Right column ----
        String patternLabel = "Pattern";
        graphics.text(font, patternLabel, rightColCenterX - font.width(patternLabel) / 2, patternPreviewTop - 14, 0xFF888888);

        int patternPanelPad = PATTERN_PREVIEW_PANEL_PAD;
        graphics.fill(rightColCenterX - PATTERN_PREVIEW_SIZE / 2 - patternPanelPad, patternPreviewTop - patternPanelPad,
                rightColCenterX + PATTERN_PREVIEW_SIZE / 2 + patternPanelPad, patternPreviewTop + PATTERN_PREVIEW_SIZE + patternPanelPad, 0xFF2B2B2B);
        graphics.pose().pushMatrix();
        graphics.pose().translate(rightColCenterX - PATTERN_PREVIEW_SIZE / 2.0F, patternPreviewTop);
        graphics.pose().scale(PATTERN_PREVIEW_SCALE, PATTERN_PREVIEW_SCALE);
        graphics.item(candidatePreviewStack(), 0, 0);
        graphics.pose().popMatrix();

        int patternIndexLabelY = patternPreviewTop + PATTERN_PREVIEW_SIZE + patternPanelPad + 6;
        String indexLabel = availablePatterns.isEmpty() ? "(no patterns)" : (patternIndex + 1) + " / " + availablePatterns.size();
        graphics.text(font, indexLabel, rightColCenterX - font.width(indexLabel) / 2, patternIndexLabelY, 0xFFAAAAAA);

        String colorLabel = "Color: " + layerColor.getName();
        int colorLabelY = patternIndexLabelY + 14;
        graphics.text(font, colorLabel, rightColCenterX - font.width(colorLabel) / 2, colorLabelY, 0xFF888888);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
