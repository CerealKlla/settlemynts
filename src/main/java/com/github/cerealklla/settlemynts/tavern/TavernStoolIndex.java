package com.github.cerealklla.settlemynts.tavern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide index from a placed Tavern Stool's own {@link GlobalPos} (a real {@code
 * com.github.cerealklla.lyfe.rest.StoolBlock}, placed by {@link TavernStoolItem}) to which
 * settlement/plot it belongs to and which {@link TavernNpcEntity} (if any) currently sits on it.
 * Direct mirror of {@code guardhouse.GuardKitStandIndex}'s own role (lets {@code
 * TavernDailySpawnTicker} resolve every known stool without a world-wide block scan), keyed by
 * position instead of entity UUID since a Tavern Stool is a plain block, not an entity.
 */
public final class TavernStoolIndex extends SavedData {

    public static final SavedDataType<TavernStoolIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "tavern_stool_index"),
            TavernStoolIndex::new,
            codec());

    private final Map<GlobalPos, StoolInfo> stools;

    TavernStoolIndex() {
        this(new HashMap<>());
    }

    private TavernStoolIndex(Map<GlobalPos, StoolInfo> stools) {
        this.stools = stools;
    }

    public record StoolInfo(UUID settlementCoreId, UUID plotId, Optional<UUID> seatedNpcId) {
        StoolInfo withSeatedNpcId(UUID npcId) {
            return new StoolInfo(settlementCoreId, plotId, Optional.ofNullable(npcId));
        }
    }

    private record Entry(GlobalPos pos, UUID settlementCoreId, UUID plotId, Optional<UUID> seatedNpcId) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                UUIDUtil.CODEC.fieldOf("settlement_core_id").forGetter(Entry::settlementCoreId),
                UUIDUtil.CODEC.fieldOf("plot_id").forGetter(Entry::plotId),
                UUIDUtil.CODEC.optionalFieldOf("seated_npc_id").forGetter(Entry::seatedNpcId)
        ).apply(i, Entry::new));
    }

    private static Codec<TavernStoolIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<GlobalPos, StoolInfo> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.pos(), new StoolInfo(entry.settlementCoreId(), entry.plotId(), entry.seatedNpcId()));
                    }
                    return new TavernStoolIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.stools.size());
                    data.stools.forEach((pos, info) ->
                            entries.add(new Entry(pos, info.settlementCoreId(), info.plotId(), info.seatedNpcId())));
                    return entries;
                });
    }

    public static TavernStoolIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(GlobalPos pos, UUID settlementCoreId, UUID plotId) {
        stools.put(pos, new StoolInfo(settlementCoreId, plotId, Optional.empty()));
        setDirty();
    }

    public void remove(GlobalPos pos) {
        if (stools.remove(pos) != null) {
            setDirty();
        }
    }

    public Optional<StoolInfo> get(GlobalPos pos) {
        return Optional.ofNullable(stools.get(pos));
    }

    public void setSeatedNpc(GlobalPos pos, UUID npcId) {
        StoolInfo info = stools.get(pos);
        if (info != null) {
            stools.put(pos, info.withSeatedNpcId(npcId));
            setDirty();
        }
    }

    public void clearSeatedNpc(GlobalPos pos) {
        StoolInfo info = stools.get(pos);
        if (info != null && info.seatedNpcId().isPresent()) {
            stools.put(pos, new StoolInfo(info.settlementCoreId(), info.plotId(), Optional.empty()));
            setDirty();
        }
    }

    /** Read-only snapshot of every known Tavern Stool -- used by {@code TavernDailySpawnTicker}. */
    public Map<GlobalPos, StoolInfo> all() {
        return Map.copyOf(stools);
    }
}
