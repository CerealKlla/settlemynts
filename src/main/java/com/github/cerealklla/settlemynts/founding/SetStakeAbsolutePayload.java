package com.github.cerealklla.settlemynts.founding;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client-to-server: toggles a stake's absolute flag from {@code client.StakeScreen} (design doc Section 6). Server enforces the 5-absolute-per-settlement cap regardless of what the client sends. */
public record SetStakeAbsolutePayload(int stakeEntityId, boolean absolute) implements CustomPacketPayload {

    public static final Type<SetStakeAbsolutePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_stake_absolute"));

    public static final StreamCodec<ByteBuf, SetStakeAbsolutePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetStakeAbsolutePayload::stakeEntityId,
            ByteBufCodecs.BOOL, SetStakeAbsolutePayload::absolute,
            SetStakeAbsolutePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
