package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entity types for the settlement-founding mechanic (design doc Sections 3/5-9). */
public final class ModEntities {

    private ModEntities() {
    }

    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(SettlemyntsMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<GhostTownHallCoreEntity>> GHOST_TOWN_HALL_CORE = ENTITIES.registerEntityType(
            "ghost_town_hall_core",
            GhostTownHallCoreEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPerimeterStakeEntity>> GHOST_PERIMETER_STAKE = ENTITIES.registerEntityType(
            "ghost_perimeter_stake",
            GhostPerimeterStakeEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));
}
