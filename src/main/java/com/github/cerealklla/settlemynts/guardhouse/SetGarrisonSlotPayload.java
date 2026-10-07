package com.github.cerealklla.settlemynts.guardhouse;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: one garrison slot's "Max Gear Tier"/"Allow Neighbor Purchase" row changed on
 * {@code client.ConfigureGarrisonScreen} -- an immediate round-trip per click, same convention as
 * {@code SetShowPlotPerimetersPayload}/{@code RepositionTownHallCorePayload}, not a batched "Save"
 * step. The server re-checks {@code zone.PlotPermissions#canManage} itself and clamps {@code
 * maxGearTier} to the Guardhouse's current Tier cap before applying ({@code
 * GuardhouseBlockEntity#setGarrisonSlot}) -- never trusts the client's own value outright.
 */
public record SetGarrisonSlotPayload(BlockPos guardhousePos, int slotIndex, int maxGearTier, boolean allowNeighborPurchase)
        implements CustomPacketPayload {

    public static final Type<SetGarrisonSlotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_garrison_slot"));

    public static final StreamCodec<ByteBuf, SetGarrisonSlotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetGarrisonSlotPayload::guardhousePos,
            ByteBufCodecs.VAR_INT, SetGarrisonSlotPayload::slotIndex,
            ByteBufCodecs.VAR_INT, SetGarrisonSlotPayload::maxGearTier,
            ByteBufCodecs.BOOL, SetGarrisonSlotPayload::allowNeighborPurchase,
            SetGarrisonSlotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
