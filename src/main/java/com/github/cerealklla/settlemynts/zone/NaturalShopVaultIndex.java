package com.github.cerealklla.settlemynts.zone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide index of each natural settlement's underground shop vaults -- one real vanilla Chest per
 * (settlement, zone type), explicit user request 2026-10-08: "generate 1 box for each zone/plot type
 * under the ground of the town somewhere to store the inventory for those shops... a real, physical,
 * vanilla chest... a player can't mess with them if they find one." Villages are overworld-only, so
 * positions are plain {@code BlockPos} (overworld assumed) rather than {@code GlobalPos}. See {@link
 * NaturalShopVault} for creation/resolution and {@link NaturalShopVaultProtectionListener} for the
 * break/open protection.
 */
public final class NaturalShopVaultIndex extends SavedData {

    public static final SavedDataType<NaturalShopVaultIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "natural_shop_vaults"),
            NaturalShopVaultIndex::new,
            codec());

    private final Map<SettlementKey, Map<Identifier, BlockPos>> vaults;

    NaturalShopVaultIndex() {
        this(new HashMap<>());
    }

    private NaturalShopVaultIndex(Map<SettlementKey, Map<Identifier, BlockPos>> vaults) {
        this.vaults = vaults;
    }

    private record Entry(SettlementKey settlement, Map<Identifier, BlockPos> byZoneType) {
        static final Codec<Map<Identifier, BlockPos>> BY_ZONE_CODEC = Codec.unboundedMap(Identifier.CODEC, BlockPos.CODEC);
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                SettlementKey.CODEC.fieldOf("settlement").forGetter(Entry::settlement),
                BY_ZONE_CODEC.fieldOf("by_zone_type").forGetter(Entry::byZoneType)
        ).apply(i, Entry::new));
    }

    private static Codec<NaturalShopVaultIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<SettlementKey, Map<Identifier, BlockPos>> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.settlement(), new HashMap<>(entry.byZoneType()));
                    }
                    return new NaturalShopVaultIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.vaults.size());
                    data.vaults.forEach((key, byZone) -> entries.add(new Entry(key, byZone)));
                    return entries;
                });
    }

    public static NaturalShopVaultIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<BlockPos> get(SettlementKey settlement, Identifier zoneTypeId) {
        Map<Identifier, BlockPos> byZone = vaults.get(settlement);
        return byZone == null ? Optional.empty() : Optional.ofNullable(byZone.get(zoneTypeId));
    }

    public void put(SettlementKey settlement, Identifier zoneTypeId, BlockPos pos) {
        vaults.computeIfAbsent(settlement, k -> new HashMap<>()).put(zoneTypeId, pos);
        setDirty();
    }

    /** Every tracked vault position -- used by {@code NaturalShopVaultProtectionListener} to build its fast lookup set. */
    public List<BlockPos> allPositions() {
        List<BlockPos> all = new ArrayList<>();
        vaults.values().forEach(byZone -> all.addAll(byZone.values()));
        return all;
    }
}
