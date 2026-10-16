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
 * Client-to-server: "Keep Spares On Hand" numeric field + Save on {@code client.GuardKitStandScreen}
 * (explicit user spec, 2026-10-16). Triggers {@code GuardKitStandPreferredInventory#recalculate} for
 * the stand's own plot once set -- see {@code SettlemyntsMod#setStandSparesOnHand}.
 */
public record SetStandSparesOnHandPayload(UUID standId, int sparesOnHand) implements CustomPacketPayload {

    public static final Type<SetStandSparesOnHandPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_stand_spares_on_hand"));

    public static final StreamCodec<ByteBuf, SetStandSparesOnHandPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetStandSparesOnHandPayload::standId,
            ByteBufCodecs.VAR_INT, SetStandSparesOnHandPayload::sparesOnHand,
            SetStandSparesOnHandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
