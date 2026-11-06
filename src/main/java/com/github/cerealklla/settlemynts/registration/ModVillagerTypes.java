package com.github.cerealklla.settlemynts.registration;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Settlemynts' first {@code VillagerType} -- added 2026-11-05, Phase B of the NPC gender feature (see
 * Settlemynts' own {@code context/decisions.md} for the full writeup, and Phase A's {@code
 * npc.NpcGender}/{@code npc.NameBank}). {@code VillagerType} is a plain built-in registry (confirmed
 * against the real decompiled source -- {@code VillagerType.bootstrap}/{@code BuiltInRegistries
 * .VILLAGER_TYPE}, the same registration shape {@link ModMobEffects} already uses for {@code
 * Registries.MOB_EFFECT}), and {@code VillagerType} itself is a plain empty marker class -- its only
 * real content is the texture a {@code VillagerProfessionLayer} resolves from its own resource key
 * (``textures/entity/villager/type/<path>.png``), so registering one under this mod's own namespace
 * needs no new model/renderer code at all, just a new texture file under this mod's own resources
 * (``assets/settlemynts/textures/entity/villager/type/female.png``) -- a deliberate, flagged
 * placeholder reskin (same body shape vanilla's own 7 built-in types already share; they only ever
 * differ by palette/costume, never model shape, so this is squarely consistent with how {@code
 * VillagerType} already works, not a reinterpretation of it). {@code farm.FarmerWorkerEntity} and its
 * three siblings (Lumberjack/Miner/Resident) assign this type directly via {@code
 * Villager#setVillagerData}/{@code VillagerData#withType} once their identity resolves to {@link
 * com.github.cerealklla.settlemynts.npc.NpcGender#FEMALE} -- {@code VillagerData} is already real
 * vanilla {@code SynchedEntityData}, so this needs no new synced field of this mod's own either
 * (unlike Guard/Tavern's Phase A, which isn't a real {@code Villager} at all).
 */
public final class ModVillagerTypes {

    private ModVillagerTypes() {
    }

    public static final DeferredRegister<VillagerType> VILLAGER_TYPES =
            DeferredRegister.create(Registries.VILLAGER_TYPE, SettlemyntsMod.MODID);

    public static final DeferredHolder<VillagerType, VillagerType> FEMALE =
            VILLAGER_TYPES.register("female", VillagerType::new);
}
