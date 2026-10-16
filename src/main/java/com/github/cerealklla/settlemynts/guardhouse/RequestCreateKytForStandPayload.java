package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Create Kyt" on {@code client.GuardKitStandScreen} (the inline name box + Confirm
 * flow, same shape as Kyt's own {@code structure.client.KytMainMenuScreen}). The server binds {@code
 * name} to the stand immediately, then opens Kyt's real Create screen for it -- see {@code
 * SettlemyntsMod#requestCreateKytForStand}.
 */
public record RequestCreateKytForStandPayload(UUID standId, String name) implements CustomPacketPayload {

    public static final Type<RequestCreateKytForStandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_create_kyt_for_stand"));

    public static final StreamCodec<ByteBuf, RequestCreateKytForStandPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RequestCreateKytForStandPayload::standId,
            ByteBufCodecs.STRING_UTF8, RequestCreateKytForStandPayload::name,
            RequestCreateKytForStandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
