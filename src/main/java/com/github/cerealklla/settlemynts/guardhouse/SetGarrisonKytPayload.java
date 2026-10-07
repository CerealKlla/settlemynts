package com.github.cerealklla.settlemynts.guardhouse;

import io.netty.buffer.ByteBuf;

import com.github.cerealklla.settlemynts.SettlemyntsMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server: "Select Kyt" on {@code client.SelectKytScreen} chose a name for one garrison
 * slot (or "Clear" -- {@code kytName} empty clears the binding). Mirrors {@link
 * SetGarrisonSlotPayload}'s shape -- an immediate round trip, not a batched "Save" step. The server
 * re-checks {@code zone.PlotPermissions#canManage} and re-resolves the {@link GarrisonSlot} itself,
 * never trusting the client's value outright.
 */
public record SetGarrisonKytPayload(BlockPos guardhousePos, int slotIndex, String kytName) implements CustomPacketPayload {

    public static final Type<SetGarrisonKytPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SettlemyntsMod.MODID, "set_garrison_kyt"));

    public static final StreamCodec<ByteBuf, SetGarrisonKytPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SetGarrisonKytPayload::guardhousePos,
            ByteBufCodecs.VAR_INT, SetGarrisonKytPayload::slotIndex,
            ByteBufCodecs.STRING_UTF8, SetGarrisonKytPayload::kytName,
            SetGarrisonKytPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
