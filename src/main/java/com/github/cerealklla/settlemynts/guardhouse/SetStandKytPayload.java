package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Select"/"Clear" on {@code client.SelectKytScreen} -- {@code kytName} blank clears the binding. */
public record SetStandKytPayload(UUID standId, String kytName) implements CustomPacketPayload {

    public static final Type<SetStandKytPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_stand_kyt"));

    public static final StreamCodec<ByteBuf, SetStandKytPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetStandKytPayload::standId,
            ByteBufCodecs.STRING_UTF8, SetStandKytPayload::kytName,
            SetStandKytPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
