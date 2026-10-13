package com.github.cerealklla.settlemynts.bridge;

import java.util.List;

import com.github.cerealklla.lyfe.api.Lyfe;
import com.github.cerealklla.lyfe.map.KnownSettlementsQuery;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Settlements a player has personal knowledge of (visited, or partially known) near a point --
 * added 2026-10-13 for the Establish Highway map picker's candidate list. Same optional-soft-
 * dependency isolation convention as every other Lyfe bridge here -- every call site must check
 * {@link #isLoaded()} itself first. Thin pass-through to {@code api.Lyfe#getKnownSettlementsNear}.
 */
public final class LyfeKnownSettlementsBridge {

    private LyfeKnownSettlementsBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    public static List<KnownSettlementsQuery.Result> getKnownSettlementsNear(ServerPlayer player, ServerLevel level, BlockPos origin, double radiusBlocks) {
        return Lyfe.getKnownSettlementsNear(player, level, origin, radiusBlocks);
    }
}
