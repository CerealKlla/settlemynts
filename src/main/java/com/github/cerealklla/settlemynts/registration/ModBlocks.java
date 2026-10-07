package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlock;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlock;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignRelocatorItem;
import com.github.cerealklla.settlemynts.rope.RopeFencePostBlock;
import com.github.cerealklla.settlemynts.rope.RopeFencePostItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** This mod's first real (non-ghost), player-placeable blocks -- see {@code rope.RopeFencePostBlock}'s own doc. */
public final class ModBlocks {

    private ModBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SettlemyntsMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SettlemyntsMod.MODID);

    // v1: extends vanilla FenceBlock for collision/behavior, and its own blockstate/item-model
    // JSON both just reference vanilla's real oak_fence models/textures directly -- "use a normal
    // fence model" (explicit user request), zero new art authored.
    public static final DeferredBlock<RopeFencePostBlock> ROPE_FENCE_POST = BLOCKS.register(
            "rope_fence_post",
            id -> new RopeFencePostBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .setId(ResourceKey.create(Registries.BLOCK, id))));

    // Icon: a normal fence (item model just points at vanilla's own oak_fence_inventory model),
    // made to glow purple like an enchanted item via ENCHANTMENT_GLINT_OVERRIDE -- same convention
    // as SettlementClaimFlagItem (explicit user request, not tied to any real enchantment).
    public static final DeferredItem<RopeFencePostItem> ROPE_FENCE_POST_ITEM = ITEMS.register(
            "rope_fence_post",
            id -> new RopeFencePostItem(ROPE_FENCE_POST.get(), new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, id))
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

    // The Plot Config Sign (design doc Section 14a) -- indestructible like Blueprynts' own
    // ConstructionBoxBlock (same reasoning: never meant to be broken by normal play, only moved via
    // its own Locator item), never player-placed directly, so it deliberately has no default
    // BlockItem either -- only PLOT_CONFIG_SIGN_LOCATOR_ITEM below ever places one.
    public static final DeferredBlock<PlotConfigSignBlock> PLOT_CONFIG_SIGN = BLOCKS.register(
            "plot_config_sign",
            id -> new PlotConfigSignBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(-1.0F, 3600000.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .setId(ResourceKey.create(Registries.BLOCK, id))));

    public static final DeferredItem<PlotConfigSignRelocatorItem> PLOT_CONFIG_SIGN_LOCATOR_ITEM = ITEMS.register(
            "plot_config_sign_locator",
            id -> new PlotConfigSignRelocatorItem(new Item.Properties()
                    .stacksTo(1)
                    .setId(ResourceKey.create(Registries.ITEM, id))));

    // The Guardhouse's own structure (design doc Section 14a) -- indestructible/never
    // player-placed, same shape as PLOT_CONFIG_SIGN above; no default BlockItem, only
    // GuardhouseSpawner ever places one.
    public static final DeferredBlock<GuardhouseBlock> GUARDHOUSE = BLOCKS.register(
            "guardhouse",
            id -> new GuardhouseBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(-1.0F, 3600000.0F)
                    .sound(SoundType.STONE)
                    .setId(ResourceKey.create(Registries.BLOCK, id))));

    // Roadways Milestone 1 (added 2026-10-06) -- Tier 1 only so far (see roadway.RoadwayPaver's own
    // doc). A plain vanilla Block, no custom subclass needed -- no block entity, no interaction
    // behavior. Indestructible via the same bedrock-like properties as PLOT_CONFIG_SIGN/GUARDHOUSE
    // above (this alone satisfies "nothing can damage a Roadway block," no Protectyons work needed).
    // No default BlockItem -- only RoadwayPaver ever places one.
    public static final DeferredBlock<Block> ROADWAY = BLOCKS.register(
            "roadway",
            id -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(-1.0F, 3600000.0F)
                    .sound(SoundType.STONE)
                    .setId(ResourceKey.create(Registries.BLOCK, id))));
}
