package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Select Kyt" button on {@code client.GuardKitStandScreen}. */
public record RequestGuardKitStandKytNamesPayload(UUID standId) implements CustomPacketPayload {

    public static final Type<RequestGuardKitStandKytNamesPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_guard_kit_stand_kyt_names"));

    public static final StreamCodec<ByteBuf, RequestGuardKitStandKytNamesPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RequestGuardKitStandKytNamesPayload::standId,
            RequestGuardKitStandKytNamesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
