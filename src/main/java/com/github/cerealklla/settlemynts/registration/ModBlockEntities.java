package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.guardhouse.GuardhouseBlockEntity;
import com.github.cerealklla.settlemynts.plotsign.PlotConfigSignBlockEntity;
import com.github.cerealklla.settlemynts.rope.RopeFencePostBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    private ModBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SettlemyntsMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RopeFencePostBlockEntity>> ROPE_FENCE_POST =
            BLOCK_ENTITIES.register("rope_fence_post",
                    () -> new BlockEntityType<>(RopeFencePostBlockEntity::new, ModBlocks.ROPE_FENCE_POST.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlotConfigSignBlockEntity>> PLOT_CONFIG_SIGN =
            BLOCK_ENTITIES.register("plot_config_sign",
                    () -> new BlockEntityType<>(PlotConfigSignBlockEntity::new, ModBlocks.PLOT_CONFIG_SIGN.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GuardhouseBlockEntity>> GUARDHOUSE =
            BLOCK_ENTITIES.register("guardhouse",
                    () -> new BlockEntityType<>(GuardhouseBlockEntity::new, ModBlocks.GUARDHOUSE.get()));
}
