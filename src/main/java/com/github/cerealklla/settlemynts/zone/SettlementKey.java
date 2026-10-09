package com.github.cerealklla.settlemynts.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerLevel;

/**
 * The real, globally-stable identity of a naturally-discovered settlement: its world's seed, paired
 * with Cartographyr's own {@code EntityId} value for it. Added 2026-10-08, real user-flagged
 * robustness gap -- Cartographyr's {@code EntityId} is just a per-world incrementing counter that
 * restarts from a small number (0/1/2...) every time a fresh world is created, so a raw {@code long}
 * settlement id is NOT a safe map key on its own: two genuinely different worlds (this suite's own
 * rapid-fire dev-test workflow recreates "New World" constantly; a real server could in principle run
 * more than one world too) can trivially both produce a settlement numbered {@code 2}. Used as the key
 * everywhere natural-settlement state is stored ({@link NaturalSettlementPlotStore}, {@link
 * NaturalVillagePlotPending}, {@link NaturalVillageZoneRecheckPending}) instead of the bare id.
 */
public record SettlementKey(long worldSeed, long settlementEntityId) {

    public static final Codec<SettlementKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("world_seed").forGetter(SettlementKey::worldSeed),
            Codec.LONG.fieldOf("settlement_entity_id").forGetter(SettlementKey::settlementEntityId)
    ).apply(i, SettlementKey::new));

    public static SettlementKey of(ServerLevel level, long settlementEntityId) {
        return new SettlementKey(level.getSeed(), settlementEntityId);
    }
}
