package com.github.cerealklla.settlemynts.zone;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Show Plot Perimeters" toggle on a finalized settlement's Town Hall Core screen. */
public record SetShowPlotPerimetersPayload(int coreEntityId, boolean visible) implements CustomPacketPayload {

    public static final Type<SetShowPlotPerimetersPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_show_plot_perimeters"));

    public static final StreamCodec<ByteBuf, SetShowPlotPerimetersPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetShowPlotPerimetersPayload::coreEntityId,
            ByteBufCodecs.BOOL, SetShowPlotPerimetersPayload::visible,
            SetShowPlotPerimetersPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
