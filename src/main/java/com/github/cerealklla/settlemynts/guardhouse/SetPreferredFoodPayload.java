package com.github.cerealklla.settlemynts.guardhouse;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Preferred Food" numeric field + Save on {@code client.GuardhouseFoodScreen}
 * (explicit user spec, 2026-10-16). Re-validated server-side via {@code
 * GuardhouseBlockEntity#canManage} (never trusts the client's own earlier flag) -- see {@code
 * SettlemyntsMod#setPreferredFood}.
 */
public record SetPreferredFoodPayload(BlockPos guardhousePos, int preferredFoodValue) implements CustomPacketPayload {

    public static final Type<SetPreferredFoodPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_preferred_food"));

    public static final StreamCodec<ByteBuf, SetPreferredFoodPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetPreferredFoodPayload::guardhousePos,
            ByteBufCodecs.VAR_INT, SetPreferredFoodPayload::preferredFoodValue,
            SetPreferredFoodPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
