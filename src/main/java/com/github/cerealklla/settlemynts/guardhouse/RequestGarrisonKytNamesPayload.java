package com.github.cerealklla.settlemynts.guardhouse;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Select Kyt" button for one garrison slot on {@code client.ConfigureGarrisonScreen}
 * -- the name list has to come from the server (Kyt's store is server-side filesystem, see {@code
 * bridge.KytGuardhouseBridge}), same reasoning as Kyt's own {@code RequestKytNameListPayload}.
 * Replied to with {@link OpenGarrisonKytPickerPayload}.
 */
public record RequestGarrisonKytNamesPayload(BlockPos guardhousePos, int slotIndex) implements CustomPacketPayload {

    public static final Type<RequestGarrisonKytNamesPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_garrison_kyt_names"));

    public static final StreamCodec<ByteBuf, RequestGarrisonKytNamesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestGarrisonKytNamesPayload::guardhousePos,
            ByteBufCodecs.VAR_INT, RequestGarrisonKytNamesPayload::slotIndex,
            RequestGarrisonKytNamesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
