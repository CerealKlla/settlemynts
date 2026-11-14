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

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Save-wide, chunk-independent record of "this guard is currently stranded away from its home
 * dimension, last known at this {@link GlobalPos}" -- real bug fix, 2026-11-13. Report: "several of
 * my settlement's guards are not respawning because the server thinks they're in the nether still."
 * Root cause: {@link GuardEntity#maintainStrandedChunkClaim} keeps a stranded guard's own chunk
 * force-loaded via {@link com.github.cerealklla.nuclyus.chunkload.ChunkClaims} so it can keep
 * ticking (and so {@link GuardReturnToDimensionGoal} gets a chance to recall it home) -- but that
 * claim is entity-side state, re-established every tick from inside the guard's own {@code
 * customServerAiStep}, which only ever runs while the guard is <i>already</i> ticking. {@code
 * ChunkClaims#validateTickets} deliberately wipes every claim on every server restart ("whatever
 * logic wanted a chunk awake re-establishes its own claim on its own next check") -- fine for every
 * other consumer, which all re-derive their claims from a reliably-loaded anchor (a Guardhouse, a
 * settlement's Town Hall), but fatal here: once the claim is wiped, the guard's chunk has no reason
 * to ever load again, so it never ticks, so it never gets the chance to re-claim itself. A chicken-
 * and-egg bootstrap gap, not a flaw in the recall goal itself (which is a fast, direct,
 * one-tick teleport once the guard is actually running again).
 *
 * <p>This index breaks that cycle: {@link GuardEntity#maintainStrandedChunkClaim} writes/clears a
 * plain {@code UUID -> GlobalPos} entry here in lockstep with its own claim/release calls (a plain
 * map write, zero chunk/entity dependency, same reliability argument as {@link
 * GuardPendingDeathIndex}), and {@code GuardSpawnTicker}'s existing 5-second per-stand scan -- which
 * runs from each Guard Kit Stand's own reliably-loaded home chunk, not the guard's -- re-issues the
 * exact same {@link com.github.cerealklla.nuclyus.chunkload.ChunkClaimId} claim for any assigned
 * guard still listed here. That's enough to force the stranded chunk to load again, at which point
 * the guard resumes ticking under its own steam, immediately recalls itself home via {@link
 * GuardReturnToDimensionGoal}, and the normal release path clears this entry for good.
 */
public final class GuardStrandedLocationIndex extends SavedData {

    public static final SavedDataType<GuardStrandedLocationIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "guard_stranded_location_index"),
            GuardStrandedLocationIndex::new,
            codec());

    private final Map<UUID, GlobalPos> locationsByGuard;

    GuardStrandedLocationIndex() {
        this(new HashMap<>());
    }

    private GuardStrandedLocationIndex(Map<UUID, GlobalPos> locationsByGuard) {
        this.locationsByGuard = locationsByGuard;
    }

    private record Entry(UUID guardId, GlobalPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("guard_id").forGetter(Entry::guardId),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos)
        ).apply(i, Entry::new));
    }

    private static Codec<GuardStrandedLocationIndex> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, GlobalPos> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.guardId(), entry.pos());
                    }
                    return new GuardStrandedLocationIndex(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.locationsByGuard.size());
                    data.locationsByGuard.forEach((guardId, pos) -> entries.add(new Entry(guardId, pos)));
                    return entries;
                });
    }

    public static GuardStrandedLocationIndex get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID guardId, GlobalPos pos) {
        locationsByGuard.put(guardId, pos);
        setDirty();
    }

    public void remove(UUID guardId) {
        if (locationsByGuard.remove(guardId) != null) {
            setDirty();
        }
    }

    public Optional<GlobalPos> get(UUID guardId) {
        return Optional.ofNullable(locationsByGuard.get(guardId));
    }
}
