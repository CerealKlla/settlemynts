package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;
import com.github.cerealklla.settlemynts.zone.PlotJob;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Change Job" picker ({@code client.ChangeJobScreen}) -- explicit user spec,
 * 2026-10-16: "allows selecting Farmer or Lumberjack for now," later clarified to "only available
 * for an NPC on a private property plot." Server re-validates ownership and the private-residence
 * zone type itself (same {@code computeNpcMenuOptions} eligibility the button's own visibility
 * already used) -- see {@code SettlemyntsMod#setPlotJob}. {@code job == PlotJob.NONE} is the
 * "Clear" option, reverting to whatever {@code zoneTypeId} would otherwise imply.
 */
public record SetPlotJobPayload(ShopAnchor.Npc anchor, PlotJob job) implements CustomPacketPayload {

    public static final Type<SetPlotJobPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_plot_job"));

    public static final StreamCodec<ByteBuf, SetPlotJobPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, payload -> payload.anchor().entityId(),
            ByteBufCodecs.STRING_UTF8.map(PlotJob::valueOf, PlotJob::name), SetPlotJobPayload::job,
            (entityId, job) -> new SetPlotJobPayload(new ShopAnchor.Npc(entityId), job));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
