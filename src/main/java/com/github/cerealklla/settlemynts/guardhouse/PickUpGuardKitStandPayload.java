package com.github.cerealklla.settlemynts.guardhouse;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: "Pick Up" on {@code client.GuardKitStandScreen}. */
public record PickUpGuardKitStandPayload(UUID standId) implements CustomPacketPayload {

    public static final Type<PickUpGuardKitStandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "pick_up_guard_kit_stand"));

    public static final StreamCodec<ByteBuf, PickUpGuardKitStandPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PickUpGuardKitStandPayload::standId,
            PickUpGuardKitStandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
