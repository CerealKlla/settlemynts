package com.github.cerealklla.settlemynts.guardhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client reply to {@link RequestGuardKitStandKytNamesPayload} -- opens {@code client.SelectKytScreen}. */
public record OpenGuardKitStandKytPickerPayload(UUID standId, List<String> names) implements CustomPacketPayload {

    public static final Type<OpenGuardKitStandKytPickerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "open_guard_kit_stand_kyt_picker"));

    public static final StreamCodec<ByteBuf, OpenGuardKitStandKytPickerPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, OpenGuardKitStandKytPickerPayload::standId,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), OpenGuardKitStandKytPickerPayload::names,
            OpenGuardKitStandKytPickerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
