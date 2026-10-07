package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client reply to {@link RequestGarrisonKytNamesPayload} -- opens {@code client.SelectKytScreen}. */
public record OpenGarrisonKytPickerPayload(BlockPos guardhousePos, int slotIndex, List<String> names) implements CustomPacketPayload {

    public static final Type<OpenGarrisonKytPickerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_garrison_kyt_picker"));

    public static final StreamCodec<ByteBuf, OpenGarrisonKytPickerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), OpenGarrisonKytPickerPayload::guardhousePos,
            ByteBufCodecs.VAR_INT, OpenGarrisonKytPickerPayload::slotIndex,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), OpenGarrisonKytPickerPayload::names,
            OpenGarrisonKytPickerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
