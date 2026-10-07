package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.founding.GhostBoundaryWallEntity;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterFencePostEntity;
import com.github.cerealklla.settlemynts.founding.GhostPerimeterStakeEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallBellEntity;
import com.github.cerealklla.settlemynts.founding.GhostTownHallCoreEntity;
import com.github.cerealklla.settlemynts.farm.FarmerWorkerEntity;
import com.github.cerealklla.settlemynts.guardhouse.GuardEntity;
import com.github.cerealklla.settlemynts.lumberyard.LumberjackWorkerEntity;
import com.github.cerealklla.settlemynts.resident.ResidentVillagerEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotFencePostEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotStakeEntity;
import com.github.cerealklla.settlemynts.zone.GhostPlotWallEntity;
import com.github.cerealklla.settlemynts.zone.GhostRoadAccessFlagEntity;
import com.github.cerealklla.settlemynts.zone.GhostRoadAccessPreviewEntity;
import com.github.cerealklla.settlemynts.roadway.RoadwayStakeEntity;
import com.github.cerealklla.settlemynts.rope.RopeAnchorEntity;

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

    // Bell marker (2026-10-05) -- replaces the Core's old floating Bell *item icon* with a real,
    // full-size Bell block. See GhostTownHallBellEntity's own doc for the same-day revert of a
    // first attempt that spawned an entire miniature building instead.
    public static final DeferredHolder<EntityType<?>, EntityType<GhostTownHallBellEntity>> GHOST_TOWN_HALL_BELL = ENTITIES.registerEntityType(
            "ghost_town_hall_bell",
            GhostTownHallBellEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    // Widened 0.5 -> 1.0 (2026-09-29, same hitbox-centering fix as GHOST_PLOT_STAKE below, applied
    // here on user request to match) -- now that the entity's own position is centered within its
    // visual block cell (see GhostBlockDisplays#setTranslation's own doc), a full-block-width
    // hitbox exactly covers the visual instead of only its near quarter.
    public static final DeferredHolder<EntityType<?>, EntityType<GhostPerimeterStakeEntity>> GHOST_PERIMETER_STAKE = ENTITIES.registerEntityType(
            "ghost_perimeter_stake",
            GhostPerimeterStakeEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostBoundaryWallEntity>> GHOST_BOUNDARY_WALL = ENTITIES.registerEntityType(
            "ghost_boundary_wall",
            GhostBoundaryWallEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    // Widened 0.5 -> 1.0 (2026-09-29, "very difficult to right click") -- now that the entity's own
    // position is centered within its visual block cell (see GhostBlockDisplays#setTranslation's own
    // doc), a full-block-width hitbox exactly covers the visual instead of only its near quarter.
    public static final DeferredHolder<EntityType<?>, EntityType<GhostPlotStakeEntity>> GHOST_PLOT_STAKE = ENTITIES.registerEntityType(
            "ghost_plot_stake",
            GhostPlotStakeEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPlotWallEntity>> GHOST_PLOT_WALL = ENTITIES.registerEntityType(
            "ghost_plot_wall",
            GhostPlotWallEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    // Roadways Milestone 1 (added 2026-10-06) -- same hitbox shape as GHOST_PLOT_STAKE above.
    public static final DeferredHolder<EntityType<?>, EntityType<RoadwayStakeEntity>> ROADWAY_STAKE = ENTITIES.registerEntityType(
            "roadway_stake",
            RoadwayStakeEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(1.0f, 1.0f));

    // Real live request, 2026-10-06: "it's very obvious which stakes are connected in the event
    // that roads overlap" -- a string of ghost torches along every edge's own centerline, same
    // "ghost preview markers" shape as GHOST_ROAD_ACCESS_PREVIEW below.
    public static final DeferredHolder<EntityType<?>, EntityType<com.github.cerealklla.settlemynts.roadway.RoadwayConnectionMarkerEntity>> ROADWAY_CONNECTION_MARKER = ENTITIES.registerEntityType(
            "roadway_connection_marker",
            com.github.cerealklla.settlemynts.roadway.RoadwayConnectionMarkerEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPerimeterFencePostEntity>> GHOST_PERIMETER_FENCE_POST = ENTITIES.registerEntityType(
            "ghost_perimeter_fence_post",
            GhostPerimeterFencePostEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostPlotFencePostEntity>> GHOST_PLOT_FENCE_POST = ENTITIES.registerEntityType(
            "ghost_plot_fence_post",
            GhostPlotFencePostEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostRoadAccessFlagEntity>> GHOST_ROAD_ACCESS_FLAG = ENTITIES.registerEntityType(
            "ghost_road_access_flag",
            GhostRoadAccessFlagEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    public static final DeferredHolder<EntityType<?>, EntityType<GhostRoadAccessPreviewEntity>> GHOST_ROAD_ACCESS_PREVIEW = ENTITIES.registerEntityType(
            "ghost_road_access_preview",
            GhostRoadAccessPreviewEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.5f, 1.0f));

    // Rope Fence rework, second pass -- a real (non-ghost) leash attachment point for a placed
    // Rope Fence Post, see RopeAnchorEntity's own doc. clientTrackingRange matches the suite's other
    // small markers; no size beyond a point since it renders nothing of its own body.
    public static final DeferredHolder<EntityType<?>, EntityType<RopeAnchorEntity>> ROPE_ANCHOR = ENTITIES.registerEntityType(
            "rope_anchor",
            RopeAnchorEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.1f, 0.1f));

    // Guardhouse garrison guard (design doc Section 14a, guard-spawning pass) -- this file's first
    // entry with real pathfinding/physics; every other entry above is a non-physical ghost marker.
    // MobCategory.MISC since this is never naturally spawned -- only ever created by
    // guardhouse.GuardSpawnTicker. Sized like a vanilla player hitbox.
    public static final DeferredHolder<EntityType<?>, EntityType<GuardEntity>> GUARD = ENTITIES.registerEntityType(
            "guard",
            GuardEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.6f, 1.95f).clientTrackingRange(10));

    // NPC-owned-plot resident (design doc Section 14a, added 2026-10-05) -- a real Villager
    // subclass, same hitbox vanilla villagers use. MobCategory.MISC, never naturally spawned --
    // only ever created by resident.ResidentSpawnTicker.
    public static final DeferredHolder<EntityType<?>, EntityType<ResidentVillagerEntity>> RESIDENT_VILLAGER = ENTITIES.registerEntityType(
            "resident_villager",
            ResidentVillagerEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.6f, 1.95f).clientTrackingRange(10));

    // Lumberyard/Farm worker NPCs (design doc Section 14a, added 2026-10-05) -- same shape as
    // RESIDENT_VILLAGER above (plain Villager subclass, real vanilla hitbox/rendering), just with a
    // real job instead of idle wandering. Never naturally spawned -- only ever created by their own
    // spawn tickers.
    public static final DeferredHolder<EntityType<?>, EntityType<LumberjackWorkerEntity>> LUMBERJACK_WORKER = ENTITIES.registerEntityType(
            "lumberjack_worker",
            LumberjackWorkerEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.6f, 1.95f).clientTrackingRange(10));

    public static final DeferredHolder<EntityType<?>, EntityType<FarmerWorkerEntity>> FARMER_WORKER = ENTITIES.registerEntityType(
            "farmer_worker",
            FarmerWorkerEntity::new,
            MobCategory.MISC,
            builder -> builder.sized(0.6f, 1.95f).clientTrackingRange(10));
}
