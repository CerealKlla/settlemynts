package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: the "Configure" tab's per-slot "Set"/"Clear" button for a Farm/Lumberyard plot's
 * Plant Selection (explicit user spec, 2026-10-28). No item id is sent -- the server resolves
 * whatever the player is actually holding in their main hand at the moment of the click and consumes
 * exactly 1 of it (a real seed/sapling, never trusted from the client); holding nothing clears the
 * slot instead. Re-validates {@code zone.PlotPermissions#canManage}, that the plot is actually
 * Farm/Lumberyard-typed, that {@code slotIndex} is within the plot's currently-unlocked Tier range,
 * and (for a Set) that the held item is a real Seed (Farm) or Sapling (Lumberyard) -- see {@code
 * SettlemyntsMod#setPlantSelection}.
 */
public record SetPlantSelectionPayload(BlockPos signPos, int slotIndex) implements CustomPacketPayload {

    public static final Type<SetPlantSelectionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_plant_selection"));

    public static final StreamCodec<ByteBuf, SetPlantSelectionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetPlantSelectionPayload::signPos,
            ByteBufCodecs.VAR_INT, SetPlantSelectionPayload::slotIndex,
            SetPlantSelectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
