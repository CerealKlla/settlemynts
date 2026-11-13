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
 * Loom/banner item to be brought to the Keep). Reusable for either banner field ({@code
 * CapitalCityKeepScreen}'s two "Design ... Banner..." buttons) -- {@code commitFactory}
 * constructs whichever payload actually writes the finished design back
 * ({@code SetCapitalCityBannerPayload}/{@code SetKingdomBannerPayload}).
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

    /** Constructs whichever payload actually commits the finished design -- {@code SetCapitalCityBannerPayload::new}/{@code SetKingdomBannerPayload::new}. */
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
        int previewTop = 30;
        int belowPreview = previewTop + PREVIEW_SIZE + 10;

        addRenderableWidget(Button.builder(Component.literal("Remove Last Layer"), b -> removeLastLayer())
                .bounds(centerX - 100, belowPreview, 200, 20).build());

        int y = belowPreview + 26;
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

    private static final int PREVIEW_SIZE = 64;

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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int titleWidth = font.width(title);
        graphics.text(font, title, centerX - titleWidth / 2, 10, 0xFFFFFFFF);

        int previewTop = 30;
        graphics.item(previewStack(), centerX - PREVIEW_SIZE / 2, previewTop);

        int listY = previewTop + PREVIEW_SIZE + 36;
        String header = draft.isEmpty() ? "No layers yet (blank banner)" : "Layers:";
        graphics.text(font, header, centerX - font.width(header) / 2, listY, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
