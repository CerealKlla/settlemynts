package com.github.cerealklla.settlemynts.plotsign.client;

import java.util.Optional;

import net.minecraft.core.BlockPos;

/**
 * Client-side holder for "is there a shop-eligible Plot Config Sign within 4 blocks right now,"
 * updated whenever a {@code PlotShopPromptPayload} arrives (2026-10-05). Same shape as Lyfe's
 * {@code location.ClientLocationState} -- harmless if classloaded on a dedicated server, it just
 * never gets written to there.
 */
public final class ClientShopPromptState {

    private static volatile Optional<BlockPos> nearestShopSign = Optional.empty();

    private ClientShopPromptState() {
    }

    public static void set(boolean present, BlockPos signPos) {
        nearestShopSign = present ? Optional.of(signPos) : Optional.empty();
    }

    public static Optional<BlockPos> get() {
        return nearestShopSign;
    }
}
