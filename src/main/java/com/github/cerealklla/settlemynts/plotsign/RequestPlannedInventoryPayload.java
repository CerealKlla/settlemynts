package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Planned Inventory" button on {@code client.PlotConfigSignMenuScreen} (2026-10-09).
 * Server re-checks {@code zone.PlotPermissions#canManage} the same way {@code RequestShopPayload}'s
 * manage mode does and replies with {@link OpenPlannedInventoryPayload}.
 */
public record RequestPlannedInventoryPayload(BlockPos signPos) implements CustomPacketPayload {

    public static final Type<RequestPlannedInventoryPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "request_planned_inventory"));

    public static final StreamCodec<ByteBuf, RequestPlannedInventoryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), RequestPlannedInventoryPayload::signPos,
            RequestPlannedInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
