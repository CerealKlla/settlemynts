package com.github.cerealklla.settlemynts.zone;

import java.util.UUID;

import com.github.cerealklla.nuclyus.chunkload.ChunkClaimId;
import com.github.cerealklla.nuclyus.chunkload.ChunkClaims;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Thin wrapper over Nuclyus's shared {@code chunkload.ChunkClaims}, tagged with a
 * {@code "proximity_wake"} reason -- a settlement's chunks are kept force-loaded whenever a player
 * is within range (see {@link SettlementProximityTicker}), independently of {@link
 * MidnightChunkWaker}'s own {@code "midnight_wake"} claim for the exact same settlement. Each
 * reason's claim id is distinct, so releasing one can never touch the other's chunks.
 */
public final class SettlementProximityWaker {

    private SettlementProximityWaker() {
    }

    private static ChunkClaimId claimId(UUID settlementId) {
        return new ChunkClaimId("settlemynts", "proximity_wake", settlementId);
    }

    public static void wake(ServerLevel level, UUID settlementId, Iterable<BlockPos> positions) {
        ChunkClaims.claim(level, claimId(settlementId), positions);
    }

    public static void sleep(ServerLevel level, UUID settlementId) {
        ChunkClaims.release(level.getServer(), claimId(settlementId));
    }
}
