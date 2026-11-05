package com.github.cerealklla.settlemynts.npc;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/** Identifies one (NPC, player) relationship -- moved here from Audyble's own {@code npc.RelationshipKey}, 2026-11-05. */
public record RelationshipKey(NpcIdentityKey npcId, UUID playerId) {

    public static final Codec<RelationshipKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            NpcIdentityKey.CODEC.fieldOf("npc_id").forGetter(RelationshipKey::npcId),
            UUIDUtil.CODEC.fieldOf("player_id").forGetter(RelationshipKey::playerId)
    ).apply(i, RelationshipKey::new));
}
