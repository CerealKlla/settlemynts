package com.github.cerealklla.settlemynts.resident;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide registry of every known {@link BedSpawnSlot} -- see that record's own doc for the full
 * redesign writeup. Keyed by the bed's own {@link BlockPos} alone, not also its dimension: natural
 * settlements are an overworld-only concept in this suite already ({@code zone.SettlementKey} has
 * no dimension field of its own either), so a bare block-position key can never collide in practice.
 * See {@link VillagerBedSpawnTicker} (discovery + fill) and {@link VillagerDeathListener}/{@link
 * VillagerConversionListener} (vacate on population loss).
 */
public final class BedSpawnSlotIndex extends SavedData {

    public static final SavedDataType<BedSpawnSlotIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "bed_spawn_slots"),
            BedSpawnSlotIndex::new,
            codec());

    private final Map<BlockPos, BedSpawnSlot> byBedPos;

    BedSpawnSlotIndex() {
        this(new HashMap<>());
    }

    private BedSpawnSlotIndex(Map<BlockPos, BedSpawnSlot> byBedPos) {
        this.byBedPos = byBedPos;
    }

    private static Codec<BedSpawnSlotIndex> codec() {
        return BedSpawnSlot.CODEC.listOf().xmap(
                list -> {
                    Map<BlockPos, BedSpawnSlot> map = new HashMap<>();
                    for (BedSpawnSlot slot : list) {
                        map.put(slot.bedPos(), slot);
                    }
                    return new BedSpawnSlotIndex(map);
                },
                data -> List.copyOf(data.byBedPos.values()));
    }

    public static BedSpawnSlotIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<BedSpawnSlot> all() {
        return List.copyOf(byBedPos.values());
    }

    public boolean isTracked(BlockPos bedPos) {
        return byBedPos.containsKey(bedPos);
    }

    public void put(BedSpawnSlot slot) {
        byBedPos.put(slot.bedPos(), slot);
        setDirty();
    }

    /** For {@link VillagerDeathListener}/{@link VillagerConversionListener} -- find whichever bed (if any) a just-lost villager was bound to, by its own entity UUID, with no position/margin guessing needed. */
    public Optional<BedSpawnSlot> findByVillager(UUID villagerId) {
        return byBedPos.values().stream()
                .filter(slot -> slot.villagerId().isPresent() && slot.villagerId().get().equals(villagerId))
                .findFirst();
    }

    /** True if some other slot already claims this villager -- used during discovery so an already-bed-bound villager is never adopted a second time by a newly-found bed. */
    public boolean isBoundElsewhere(UUID villagerId, BlockPos excludingBedPos) {
        return byBedPos.values().stream().anyMatch(slot -> !slot.bedPos().equals(excludingBedPos)
                && slot.villagerId().isPresent() && slot.villagerId().get().equals(villagerId));
    }
}
