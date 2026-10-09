package com.github.cerealklla.settlemynts.plotsign;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: opens {@code client.UpgradePlotScreen} with a live, server-computed cost/
 * affordability breakdown (see {@code construction.PlotTierUpgradeFunding#preview}) -- added
 * 2026-10-09. Parallel lists (one entry per required resource) rather than a nested record, to keep
 * the {@code StreamCodec} simple; {@code enabledFlags} packs the three button-enabled booleans into
 * one bitmask (vanilla's {@code StreamCodec#composite} tops out at 12 fields, one more than this
 * payload would otherwise need) -- {@link #onHandEnabled()}/{@link #mixEnabled()}/{@link
 * #goldEnabled()} unpack it. These already fold in both sourceability (plot stock + settlement
 * economy, stock-aware) and the player's own gold balance -- the screen just disables a button
 * directly off these, no further client-side math needed.
 */
public record UpgradePlotPreviewPayload(BlockPos signPos, int nextTier,
                                         List<String> resourceLabels, List<Integer> resourceAmounts, List<Integer> resourceOnHand,
                                         List<Integer> mixCosts, List<Boolean> mixCovered,
                                         List<Integer> goldCosts, List<Boolean> goldCovered,
                                         int mixTotalCost, int goldTotalCost, int enabledFlags) implements CustomPacketPayload {

    private static final int ON_HAND_ENABLED_BIT = 1;
    private static final int MIX_ENABLED_BIT = 2;
    private static final int GOLD_ENABLED_BIT = 4;

    public static int packFlags(boolean onHandEnabled, boolean mixEnabled, boolean goldEnabled) {
        int flags = 0;
        if (onHandEnabled) {
            flags |= ON_HAND_ENABLED_BIT;
        }
        if (mixEnabled) {
            flags |= MIX_ENABLED_BIT;
        }
        if (goldEnabled) {
            flags |= GOLD_ENABLED_BIT;
        }
        return flags;
    }

    public boolean onHandEnabled() {
        return (enabledFlags & ON_HAND_ENABLED_BIT) != 0;
    }

    public boolean mixEnabled() {
        return (enabledFlags & MIX_ENABLED_BIT) != 0;
    }

    public boolean goldEnabled() {
        return (enabledFlags & GOLD_ENABLED_BIT) != 0;
    }

    public static final Type<UpgradePlotPreviewPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "upgrade_plot_preview"));

    public static final StreamCodec<ByteBuf, UpgradePlotPreviewPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), UpgradePlotPreviewPayload::signPos,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::nextTier,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), UpgradePlotPreviewPayload::resourceLabels,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), UpgradePlotPreviewPayload::resourceAmounts,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), UpgradePlotPreviewPayload::resourceOnHand,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), UpgradePlotPreviewPayload::mixCosts,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.BOOL), UpgradePlotPreviewPayload::mixCovered,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), UpgradePlotPreviewPayload::goldCosts,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.BOOL), UpgradePlotPreviewPayload::goldCovered,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::mixTotalCost,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::goldTotalCost,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::enabledFlags,
            UpgradePlotPreviewPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
