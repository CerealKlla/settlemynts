package com.github.cerealklla.settlemynts.founding.client;

import java.util.Optional;

import net.minecraft.core.BlockPos;

/**
 * Client-side holder for "the live position of the stake I'm currently anchored to" (Plot Placement
 * Stake or Roadway Stake), updated whenever an {@code AnchorDistancePayload} arrives -- see that
 * class's own doc. Same shape as {@code plotsign.client.ClientShopPromptState}.
 */
public final class ClientAnchorDistanceState {

    private static volatile Optional<BlockPos> anchorPos = Optional.empty();

    private ClientAnchorDistanceState() {
    }

    public static void set(boolean present, BlockPos pos) {
        anchorPos = present ? Optional.of(pos) : Optional.empty();
    }

    public static Optional<BlockPos> get() {
        return anchorPos;
    }
}
