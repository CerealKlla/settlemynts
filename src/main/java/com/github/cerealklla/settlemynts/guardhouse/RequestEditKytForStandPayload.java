package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Edit Kyt" on {@code client.GuardKitStandScreen} -- opens Kyt's real Edit screen for the stand's already-bound name. */
public record RequestEditKytForStandPayload(UUID standId) implements CustomPacketPayload {

    public static final Type<RequestEditKytForStandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_edit_kyt_for_stand"));

    public static final StreamCodec<ByteBuf, RequestEditKytForStandPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RequestEditKytForStandPayload::standId,
            RequestEditKytForStandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
