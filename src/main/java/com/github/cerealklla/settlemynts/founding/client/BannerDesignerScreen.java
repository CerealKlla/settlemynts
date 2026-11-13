package com.github.cerealklla.settlemynts.founding.client;

import java.util.ArrayList;
import java.util.List;

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

    private Button addLayerButton;
    private Button patternButton;
    private Button layerColorButton;

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
        // Real layout bug, fixed 2026-11-13: the header text below the preview and the
        // Pattern/Color button row used to land on the exact same y (both derived from
        // `belowPreview`, one with a +36 offset and the other with a +26-then-further-unrelated
        // offset that happened to coincide) -- confirmed by the live screenshot showing "No layers
        // yet (blank banner)" printed directly through the Pattern/Color buttons. Every row below
        // is now a strictly increasing cursor, matching the preview's own real (now 4x-scaled, see
        // PREVIEW_SCALE) footprint instead of the old purely-decorative PREVIEW_SIZE reservation.
        int previewTop = 30;
        int headerY = previewTop + PREVIEW_SIZE + 14;
        int removeLastLayerY = headerY + 14;

        addRenderableWidget(Button.builder(Component.literal("Remove Last Layer"), b -> removeLastLayer())
                .bounds(centerX - 100, removeLastLayerY, 200, 20).build());

        int y = removeLastLayerY + 26;
        patternButton = addRenderableWidget(Button.builder(patternLabel(), b -> cyclePattern())
                .bounds(centerX - 100, y, 140, 20).build());
        layerColorButton = addRenderableWidget(Button.builder(colorLabel(), b -> cycleLayerColor())
                .bounds(centerX + 45, y, 100, 20).build());
        y += 24;
        addLayerButton = addRenderableWidget(Button.builder(Component.literal("Add Layer"), b -> addLayer())
                .bounds(centerX - 100, y, 245, 20).build());
        updateAddLayerActive();
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(centerX - 100, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(centerX + 5, y, 100, 20).build());
    }

    /** The on-screen box the preview occupies, in pixels -- the actual rendered icon is scaled up to fill it, see {@link #PREVIEW_SCALE}. */
    private static final int PREVIEW_SIZE = 64;
    /** Vanilla's own GUI item icon is a fixed 16px ({@code GuiGraphicsExtractor#item} takes no scale parameter) -- scaled up via a manual {@code pose()} transform instead, confirmed safe against the real decompiled source (the current pose IS captured into the render state at the moment {@code item(...)} is called). */
    private static final float PREVIEW_SCALE = PREVIEW_SIZE / 16.0F;

    private void cyclePattern() {
        if (availablePatterns.isEmpty()) {
            return;
        }
        patternIndex = (patternIndex + 1) % availablePatterns.size();
        patternButton.setMessage(patternLabel());
    }

    private Component patternLabel() {
        if (availablePatterns.isEmpty()) {
            return Component.literal("(no patterns)");
        }
        return Component.literal("Pattern: " + (patternIndex + 1) + "/" + availablePatterns.size());
    }

    private void cycleLayerColor() {
        DyeColor[] values = DyeColor.values();
        layerColor = values[(layerColor.getId() + 1) % values.length];
        layerColorButton.setMessage(colorLabel());
    }

    private Component colorLabel() {
        return Component.literal("Color: " + layerColor.getName());
    }

    private void addLayer() {
        if (availablePatterns.isEmpty() || draft.size() >= BannerBlockEntity.MAX_PATTERNS) {
            return;
        }
        draft.add(new BannerPatternLayers.Layer(availablePatterns.get(patternIndex), layerColor));
        updateAddLayerActive();
    }

    private void removeLastLayer() {
        if (!draft.isEmpty()) {
            draft.remove(draft.size() - 1);
            updateAddLayerActive();
        }
    }

    private void updateAddLayerActive() {
        if (addLayerButton != null) {
            addLayerButton.active = draft.size() < BannerBlockEntity.MAX_PATTERNS && !availablePatterns.isEmpty();
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

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Matches nuclyus.menu.client.NuclyusMenuScreen's own backdrop -- see this class's own doc
        // on the whole Town Hall Core screen family being restyled to the same look, 2026-11-13.
        // Real, concrete side effect this fixes on its own: the live preview below used to render
        // over whatever was directly behind this screen (sky/grass), making a small banner icon
        // nearly invisible -- a dark backdrop plus the panel behind the preview (see
        // extractRenderState) gives it real contrast for the first time.
        graphics.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 10, 0xFFFFFFFF);

        int previewTop = 30;
        int panelPad = 6;
        graphics.fill(centerX - PREVIEW_SIZE / 2 - panelPad, previewTop - panelPad,
                centerX + PREVIEW_SIZE / 2 + panelPad, previewTop + PREVIEW_SIZE + panelPad, 0xFF2B2B2B);

        // Real preview fix, 2026-11-13 -- a live screenshot showed the preview as a near-invisible
        // tiny red sliver; GuiGraphicsExtractor#item has no scale parameter of its own, but it does
        // capture whatever the current pose() transform is at the moment it's called (confirmed
        // against the real decompiled source), so a manual push/scale/pop around the call genuinely
        // scales the rendered icon -- this was flagged as a known, deliberately-not-pursued
        // limitation when this screen was first built; pursuing it now that it's a real reported
        // problem, not a cosmetic nice-to-have.
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX - PREVIEW_SIZE / 2.0F, previewTop);
        graphics.pose().scale(PREVIEW_SCALE, PREVIEW_SCALE);
        graphics.item(previewStack(), 0, 0);
        graphics.pose().popMatrix();

        int headerY = previewTop + PREVIEW_SIZE + 14;
        String header = draft.isEmpty()
                ? "No layers yet (blank banner)"
                : "Layers: " + draft.size() + "/" + BannerBlockEntity.MAX_PATTERNS;
        graphics.text(font, header, centerX - font.width(header) / 2, headerY, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
