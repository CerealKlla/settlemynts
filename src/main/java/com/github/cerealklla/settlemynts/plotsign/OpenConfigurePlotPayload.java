package com.github.cerealklla.settlemynts.plotsign;

import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.mineshaft.MineSpecialization;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client reply to {@link RequestConfigurePlotPayload} -- opens/refreshes the "Configure"
 * tab's content. {@code specialization}/{@code tier} are only meaningful for a Mineshaft plot (the
 * tab only draws the Ore/Stone control and the find-odds display when the Main Menu payload's own
 * {@code isMineshaft} was true); harmless otherwise. {@code tier} added 2026-10-28 so the new
 * find-odds display (see {@code mineshaft.MineLootTable#entriesFor}) knows which tier's table to show.
 *
 * <p>{@code plantSelections} (added 2026-10-28, Farm/Lumberyard Plant Selection) -- always exactly
 * {@code zone.WorkerPlantSelection#MAX_SLOTS} entries (real item id, or {@code ""} for a blank slot),
 * only meaningful when the Main Menu payload's own {@code isFarm}/{@code isLumberyard} was true.
 */
public record OpenConfigurePlotPayload(BlockPos signPos, MineSpecialization specialization, int tier, List<String> plantSelections) implements CustomPacketPayload {

    public static final Type<OpenConfigurePlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_configure_plot"));

    public static final StreamCodec<ByteBuf, OpenConfigurePlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenConfigurePlotPayload::signPos,
            ByteBufCodecs.STRING_UTF8.map(MineSpecialization::valueOf, MineSpecialization::name), OpenConfigurePlotPayload::specialization,
            ByteBufCodecs.VAR_INT, OpenConfigurePlotPayload::tier,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenConfigurePlotPayload::plantSelections,
            OpenConfigurePlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
