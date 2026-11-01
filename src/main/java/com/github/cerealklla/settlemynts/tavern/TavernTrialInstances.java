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

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Which {@link TavernNpcEntity} a given Myssions Trial-quest instance is actually for -- a real
 * Myssions {@code QuestCompletionHooks} callback only ever receives the instance's own {@code
 * instanceId} (see {@code bridge.MyssionsQuestBridge#registerTavernTrialCompletionHook}'s own doc),
 * so this small side table is how {@code TavernHireHandler} resolves which specific NPC to actually
 * complete the hire for once a Trial is won. Written the moment a Trial starts, removed once it
 * resolves (won, lost, or abandoned) -- never grows unbounded.
 */
public final class TavernTrialInstances extends SavedData {

    public static final SavedDataType<TavernTrialInstances> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "tavern_trial_instances"),
            TavernTrialInstances::new,
            codec());

    private final Map<UUID, UUID> instanceToNpc;

    TavernTrialInstances() {
        this(new HashMap<>());
    }

    private TavernTrialInstances(Map<UUID, UUID> instanceToNpc) {
        this.instanceToNpc = instanceToNpc;
    }

    private record Entry(UUID instanceId, UUID npcId) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("instance_id").forGetter(Entry::instanceId),
                UUIDUtil.CODEC.fieldOf("npc_id").forGetter(Entry::npcId)
        ).apply(i, Entry::new));
    }

    private static Codec<TavernTrialInstances> codec() {
        return Codec.list(Entry.CODEC).xmap(
                entries -> {
                    Map<UUID, UUID> map = new HashMap<>();
                    for (Entry entry : entries) {
                        map.put(entry.instanceId(), entry.npcId());
                    }
                    return new TavernTrialInstances(map);
                },
                data -> {
                    List<Entry> entries = new ArrayList<>(data.instanceToNpc.size());
                    data.instanceToNpc.forEach((instanceId, npcId) -> entries.add(new Entry(instanceId, npcId)));
                    return entries;
                });
    }

    public static TavernTrialInstances get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(UUID instanceId, UUID npcId) {
        instanceToNpc.put(instanceId, npcId);
        setDirty();
    }

    public Optional<UUID> remove(UUID instanceId) {
        UUID npcId = instanceToNpc.remove(instanceId);
        if (npcId != null) {
            setDirty();
        }
        return Optional.ofNullable(npcId);
    }
}
