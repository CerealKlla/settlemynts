package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostBoundaryWallEntity;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotWallEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entity types for the settlement-founding mechanic (design doc Sections 3/5-9). */
public final class ModEntities {

    private ModEntities() {
    }

    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(SettlemyntsMod.MODID);

    // clientTrackingRange is in CHUNKS, not blocks -- the vanilla default (5 chunks = 80 blocks)
    // was a real playtest bug (2026-09-26): the entity simply stops being sent to the client
    // beyond that range, so anything reading it client-side (the stake distance HUD) silently
    // lost it well within the 500 ft/1000 ft ranges this mod actually cares about. 20 chunks =
    // 320 blocks, comfortably covers the user's requested 1000 ft (~305 blocks) with margin.
    private static final int CORE_TRACKING_RANGE_CHUNKS = 20;

    public static final DeferredHolder<EntityType<?>, EntityType<GhostTownHallCoreEntity>> GHOST_TOWN_HALL_CORE = ENTITIES.registerEntityType(
            "ghost_town_hall_core",
            GhostTownHallCoreEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f).clientTrackingRange(CORE_TRACKING_RANGE_CHUNKS));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPerimeterStakeEntity>> GHOST_PERIMETER_STAKE = ENTITIES.registerEntityType(
            "ghost_perimeter_stake",
            GhostPerimeterStakeEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostBoundaryWallEntity>> GHOST_BOUNDARY_WALL = ENTITIES.registerEntityType(
            "ghost_boundary_wall",
            GhostBoundaryWallEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPlotStakeEntity>> GHOST_PLOT_STAKE = ENTITIES.registerEntityType(
            "ghost_plot_stake",
            GhostPlotStakeEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPlotWallEntity>> GHOST_PLOT_WALL = ENTITIES.registerEntityType(
            "ghost_plot_wall",
            GhostPlotWallEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));
}
