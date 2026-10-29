package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide, chunk-independent record of "this Guard Kit Stand's guard just died, and owes a
 * respawnReadyAtGameTime of X" -- real bug fix, 2026-10-29. {@link GuardEntity#die} used to notify
 * its stand directly by resolving it as a live entity, which silently did nothing if the stand's
 * own chunk wasn't loaded at that exact instant (e.g. the guard died in the Nether while nobody was
 * near the Overworld settlement) -- permanently leaving {@code assignedGuardId} stale. A follow-up
 * attempt fixed the *symptom* by having {@code GuardSpawnTicker} re-validate liveness via a fresh
 * entity lookup -- but {@code ServerLevel#getEntity(UUID)} only searches entities loaded within
 * *that one dimension's* level (this mod has no cross-dimension entity lookup anywhere, per {@link
 * GuardKitStandIndex}'s own doc on exactly why it exists), so that re-check would also misreport a
 * Guard that's genuinely alive but simply standing in a different dimension (e.g. using {@link
 * GuardReturnToDimensionGoal} to path home) as dead -- spawning a real duplicate the moment the
 * stand's chunk came back into range. Reverted outright in favor of this: make the death
 * notification itself reliable, not the liveness re-check. Writing here is a plain {@code Map}
 * operation with zero chunk/entity/dimension dependency, so it always succeeds at the real,
 * authoritative moment of death -- {@link GuardSpawnTicker} then applies (and consumes) any pending
 * entry the next time it actually processes that stand, which is already naturally gated on the
 * stand's own chunk being loaded, same as every other per-stand mutation that ticker does.
 */
public final class GuardPendingDeathIndex extends SavedData {

    public static final SavedDataType<GuardPendingDeathIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "guard_pending_death_index"),
            GuardPendingDeathIndex::new,
            codec());

    private final Map<UUID, Long> respawnReadyAtGameTimeByStand;

    GuardPendingDeathIndex() {
        this(new HashMap<>());
    }

    private GuardPendingDeathIndex(Map<UUID, Long> respawnReadyAtGameTimeByStand) {
        this.respawnReadyAtGameTimeByStand = respawnReadyAtGameTimeByStand;
    }

    private record Entry(UUID standId, long respawnReadyAtGameTime) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("stand_id").forGetter(Entry::standId),
                Codec.LONG.fieldOf("respawn_ready_at_game_time").forGetter(Entry::respawnReadyAtGameTime)
        ).apply(i, Entry::new));
    }

    private static Codec<GuardPendingDeathIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, Long> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.standId(), entry.respawnReadyAtGameTime());
                    }
                    return new GuardPendingDeathIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.respawnReadyAtGameTimeByStand.size());
                    data.respawnReadyAtGameTimeByStand.forEach((standId, time) -> entries.add(new Entry(standId, time)));
                    return entries;
                });
    }

    public static GuardPendingDeathIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void recordDeath(UUID standId, long respawnReadyAtGameTime) {
        respawnReadyAtGameTimeByStand.put(standId, respawnReadyAtGameTime);
        setDirty();
    }

    /** Removes and returns the pending death record for {@code standId}, if any -- consumed exactly once, by whichever {@code GuardSpawnTicker} pass actually applies it. */
    public Optional<Long> take(UUID standId) {
        Long value = respawnReadyAtGameTimeByStand.remove(standId);
        if (value != null) {
            setDirty();
        }
        return Optional.ofNullable(value);
    }
}
