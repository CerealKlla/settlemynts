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
 * 2026-10-09. Per-resource fields are grouped into {@link ResourceRow} (its own small
 * {@code StreamCodec}) rather than parallel lists -- vanilla's {@code StreamCodec#composite} tops out
 * at 12 fields, and the original 7-parallel-list shape left no room for {@code goldOnPlot}/
 * {@code goldOnPerson} (added the same day, "I'd like to see ... Gold: On Plot #, On Person #")
 * without this restructure. {@code enabledFlags} still packs the three button-enabled booleans into
 * one bitmask -- {@link #onHandEnabled()}/{@link #mixEnabled()}/{@link #goldEnabled()} unpack it.
 * These already fold in both sourceability (plot stock + settlement economy, stock-aware) and the
 * player's own gold balance -- the screen just disables a button directly off these, no further
 * client-side math needed.
 */
public record UpgradePlotPreviewPayload(BlockPos signPos, int nextTier, List<ResourceRow> resources,
                                         int mixTotalCost, int goldTotalCost, int enabledFlags,
                                         int goldOnPlot, int goldOnPerson,
                                         List<String> newlyAllowedZoneTypeLabels) implements CustomPacketPayload {

    /** One resource row for {@code client.UpgradePlotScreen} -- see {@code construction.PlotTierUpgradeFunding.ResourcePreviewEntry}, which this mirrors 1:1 over the wire. */
    public record ResourceRow(String label, int amount, int onHand, int mixCost, boolean mixCovered, int goldCost, boolean goldCovered) {
        public static final StreamCodec<ByteBuf, ResourceRow> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ResourceRow::label,
                ByteBufCodecs.VAR_INT, ResourceRow::amount,
                ByteBufCodecs.VAR_INT, ResourceRow::onHand,
                ByteBufCodecs.VAR_INT, ResourceRow::mixCost,
                ByteBufCodecs.BOOL, ResourceRow::mixCovered,
                ByteBufCodecs.VAR_INT, ResourceRow::goldCost,
                ByteBufCodecs.BOOL, ResourceRow::goldCovered,
                ResourceRow::new);
    }

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
            ByteBufCodecs.collection(ArrayList::new, ResourceRow.STREAM_CODEC), UpgradePlotPreviewPayload::resources,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::mixTotalCost,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::goldTotalCost,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::enabledFlags,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::goldOnPlot,
            ByteBufCodecs.VAR_INT, UpgradePlotPreviewPayload::goldOnPerson,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), UpgradePlotPreviewPayload::newlyAllowedZoneTypeLabels,
            UpgradePlotPreviewPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
