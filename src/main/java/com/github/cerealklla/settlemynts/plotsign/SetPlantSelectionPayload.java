package com.github.cerealklla.settlemynts.plotsign;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: the "Configure" tab's per-slot Plant Selection picker for a Farm/Lumberyard plot
 * (reworked 2026-10-28 to a clickable icon-grid picker, matching Wainwright Station's species
 * selector, after explicit user feedback that the original "hold the item and click the slot" flow
 * was unworkable). {@code itemId} is the chosen Seed/Sapling's registry id as a string, or blank to
 * clear the slot. The server never trusts this id blindly -- it re-validates the real item tag and
 * consumes exactly 1 of it from the player's own main inventory (not necessarily their held item)
 * before committing the change; see {@code SettlemyntsMod#setPlantSelection}. Also re-validates
 * {@code zone.PlotPermissions#canManage}, that the plot is actually Farm/Lumberyard-typed, and that
 * {@code slotIndex} is within the plot's currently-unlocked Tier range.
 */
public record SetPlantSelectionPayload(BlockPos signPos, int slotIndex, String itemId) implements CustomPacketPayload {

    public static final Type<SetPlantSelectionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_plant_selection"));

    public static final StreamCodec<ByteBuf, SetPlantSelectionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetPlantSelectionPayload::signPos,
            ByteBufCodecs.VAR_INT, SetPlantSelectionPayload::slotIndex,
            ByteBufCodecs.STRING_UTF8, SetPlantSelectionPayload::itemId,
            SetPlantSelectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
